package org.l2start.platform;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2start.platform.api.GameModule;
import org.l2start.platform.config.ModuleSwitches;
import org.l2start.platform.config.PlatformSettings;
import org.l2start.platform.config.PlatformSettings.FailurePolicy;
import org.l2start.platform.config.ResolvedConfig;
import org.l2start.platform.db.MigrationRunner;
import org.l2start.platform.db.MigrationScanner;
import org.l2start.platform.loader.DataType;
import org.l2start.platform.loader.DependencyResolver;
import org.l2start.platform.loader.IdScanner;
import org.l2start.platform.loader.ManifestException;
import org.l2start.platform.loader.ManifestReader;
import org.l2start.platform.loader.ModuleClassLoader;
import org.l2start.platform.loader.ModuleManifest;
import org.l2start.platform.loader.ModuleManifest.Dependency;
import org.l2start.platform.loader.ModuleState;
import org.l2start.platform.runtime.CircuitBreaker;
import org.l2start.platform.runtime.Messages;
import org.l2start.platform.runtime.ModuleEntry;
import org.l2start.platform.runtime.ModuleGuard;
import org.l2start.platform.runtime.ModuleRuntime;
import org.l2start.platform.runtime.ServiceDirectory;
import org.l2start.platform.util.Semver;

/**
 * The module platform. Driven by the core hooks (see docs/PLATFORM_HOOKS.md):
 * bootstrap (discover, validate, resolve, migrate, load) -> data hooks -> enable -> ready -> shutdown.
 */
public final class Platform {
    public static final Semver API_VERSION = Semver.parse("1.12.0");
    private static final Logger LOGGER = Logger.getLogger("Platform");
    private static final String HTML_PREFIX = "data/html/modules/";

    private static volatile Platform instance;

    private final Path configDir;
    private final Map<String, String> env;
    private PlatformSettings settings;
    private ModuleSwitches switches;
    private MigrationRunner migrations;
    private final Map<String, ModuleEntry> entries = new LinkedHashMap<>(); // discovery order
    private List<String> order = List.of();
    private final ServiceDirectory services = new ServiceDirectory();
    private final Map<String, String> htmlCache = new ConcurrentHashMap<>();
    private volatile boolean active;
    private volatile boolean started;
    private long bootstrapMillis;

    Platform(Path configDir, Map<String, String> env) {
        this.configDir = configDir;
        this.env = env;
    }

    public static Platform get() {
        Platform p = instance;
        if (p == null) {
            synchronized (Platform.class) {
                p = instance;
                if (p == null) {
                    p = new Platform(Paths.get("config"), System.getenv());
                    instance = p;
                }
            }
        }
        return p;
    }

    public PlatformSettings settings() {
        return settings;
    }

    public boolean isActive() {
        return active;
    }

    public synchronized List<ModuleEntry> entries() {
        return new ArrayList<>(entries.values());
    }

    public synchronized ModuleEntry entry(String id) {
        return entries.get(id);
    }

    // ------------------------------------------------------------------ bootstrap (hook P1-BOOT)

    public synchronized void bootstrap() {
        final long start = System.currentTimeMillis();
        try {
            settings = PlatformSettings.load(configDir, env);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Platform: cannot read config/platform.ini, modules are disabled", e);
            settings = PlatformSettings.defaults(PlatformSettings.profile(env));
            return;
        }
        for (String w : settings.warnings()) {
            LOGGER.warning("Platform: platform.ini: " + w);
        }
        if (settings.disableAll()) {
            LOGGER.info("Platform: DisableAll = true, no modules are loaded (stock server).");
            return;
        }
        active = true;
        LOGGER.info("Platform: API " + API_VERSION + ", profile " + settings.profile() + ", failure policy " + settings.failurePolicy().name().toLowerCase() + " (" + settings.failurePolicySource() + "), roots " + settings.moduleRoots());
        try {
            switches = ModuleSwitches.load(configDir, settings.profile(), env);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Platform: cannot read config/modules.ini", e);
            fatalIfAbort("config/modules.ini is broken: " + e.getMessage());
            active = false;
            return;
        }
        migrations = new MigrationRunner(org.l2jmobius.commons.database.DatabaseFactory::getConnection);

        discover();
        validate();
        resolve();
        migrate();
        load();
        bootstrapMillis = System.currentTimeMillis() - start;
        checkpoint("bootstrap");
    }

    private void discover() {
        for (String root : settings.moduleRoots()) {
            final Path dir = Paths.get(root);
            if (!Files.isDirectory(dir)) {
                LOGGER.info("Platform: module root " + dir.toAbsolutePath().normalize() + " does not exist, skipped");
                continue;
            }
            final List<Path> dirs = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p) && !p.getFileName().toString().startsWith("_") && !p.getFileName().toString().startsWith(".")) {
                        dirs.add(p);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Platform: cannot list " + dir, e);
            }
            Collections.sort(dirs);
            for (Path p : dirs) {
                final String id = p.getFileName().toString();
                if (!Files.isRegularFile(p.resolve(ManifestReader.FILE))) {
                    continue;
                }
                final ModuleEntry e = new ModuleEntry(id, p.toAbsolutePath().normalize());
                if (entries.containsKey(id)) {
                    LOGGER.warning("Platform: module " + id + " in " + p + " ignored: already found in " + entries.get(id).dir);
                    continue;
                }
                entries.put(id, e);
            }
        }
    }

    private void validate() {
        for (ModuleEntry e : entries.values()) {
            // A module switched off explicitly is not validated at all.
            if (switches.isExplicit(e.folderId) && !switches.resolve(e.folderId, false).enabled()) {
                e.switchSource = switches.resolve(e.folderId, false).source();
                e.set(ModuleState.DISABLED, "off in " + e.switchSource);
                continue;
            }
            try {
                e.manifest = ManifestReader.read(e.dir);
                e.warnings.addAll(e.manifest.warnings());
            } catch (ManifestException ex) {
                e.set(ModuleState.INVALID, String.join("; ", ex.errors()));
                continue;
            }
            final ModuleSwitches.Switch sw = switches.resolve(e.id(), e.manifest.defaultEnabled(settings.profile()));
            e.switchSource = sw.source();
            if (!sw.enabled()) {
                e.set(ModuleState.DISABLED, "off by " + sw.source());
                continue;
            }
            final List<String> errors = new ArrayList<>();
            try {
                for (DataType t : e.manifest.data().keySet()) {
                    if (!t.hasIds()) {
                        continue;
                    }
                    final TreeSet<Integer> ids = IdScanner.scan(t, e.dataDir(t), errors);
                    for (int id : ids) {
                        if (!e.manifest.reserves(t, id)) {
                            errors.add(t.key() + " id " + id + " is outside reserves." + t.key());
                        }
                    }
                    e.ids.put(t, ids);
                }
                final Path sql = e.dir.resolve(e.manifest.migrations());
                e.migrations = MigrationScanner.scan(sql, errors);
            } catch (IOException ex) {
                errors.add("cannot read module files: " + ex.getMessage());
            }
            if (e.manifest.kind() == ModuleManifest.Kind.JAR && !Files.isRegularFile(jarOf(e))) {
                errors.add("jar " + jarOf(e).getFileName() + " not found (module not built?)");
            }
            try {
                e.config = ResolvedConfig.load(e.manifest, e.dir, configDir, settings.profile(), env);
                e.warnings.addAll(e.config.warnings());
            } catch (ResolvedConfig.ConfigException ex) {
                errors.add(ex.getMessage());
            }
            if (!errors.isEmpty()) {
                e.set(ModuleState.INVALID, String.join("; ", errors));
            }
            for (String w : e.warnings) {
                LOGGER.warning("Platform: " + e.id() + ": " + w);
            }
        }
    }

    private static Path jarOf(ModuleEntry e) {
        return e.dir.resolve(e.id() + ".jar");
    }

    private void resolve() {
        final List<ModuleManifest> candidates = new ArrayList<>();
        final Set<String> unavailable = new HashSet<>();
        for (ModuleEntry e : entries.values()) {
            if (e.state == ModuleState.DISCOVERED) {
                candidates.add(e.manifest);
            } else {
                unavailable.add(e.id());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        final DependencyResolver.Result r = DependencyResolver.resolve(candidates, unavailable, API_VERSION, migrations.lastVersions());
        for (String w : r.warnings()) {
            LOGGER.warning("Platform: " + w);
        }
        for (Map.Entry<String, String> x : r.refused().entrySet()) {
            entries.get(x.getKey()).set(ModuleState.REFUSED, x.getValue());
        }
        for (String id : r.order()) {
            final ModuleEntry e = entries.get(id);
            e.set(ModuleState.RESOLVED, null);
            e.visibleDependencies = r.visibleDependencies().getOrDefault(id, List.of());
        }
        order = r.order();
    }

    private void migrate() {
        if (order.isEmpty()) {
            return;
        }
        try {
            migrations.ensurePlatformTables(API_VERSION.toString());
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Platform: cannot create platform tables", ex);
            for (String id : order) {
                entries.get(id).set(ModuleState.FAILED, "platform tables: " + ex.getMessage());
            }
            return;
        }
        for (String id : order) {
            final ModuleEntry e = entries.get(id);
            if (e.state != ModuleState.RESOLVED || cascadeBlocked(e)) {
                continue;
            }
            try {
                e.appliedMigrations = migrations.migrate(id, e.version(), e.migrations);
            } catch (Exception ex) {
                e.set(ModuleState.FAILED, ex.getMessage());
            }
        }
    }

    private void load() {
        for (String id : order) {
            final ModuleEntry e = entries.get(id);
            if (e.state != ModuleState.RESOLVED || cascadeBlocked(e)) {
                continue;
            }
            try {
                e.runtime = new ModuleRuntime(e, settings.profile(), newGuard(e), services, Messages.load(e.dir.resolve("i18n"), settings.defaultLanguage()));
                if (e.manifest.kind() == ModuleManifest.Kind.JAR) {
                    final List<ModuleClassLoader> deps = new ArrayList<>();
                    for (String d : e.visibleDependencies) {
                        final ModuleEntry de = entries.get(d);
                        if (de.classLoader != null) {
                            deps.add(de.classLoader);
                        }
                    }
                    e.classLoader = new ModuleClassLoader(id, new URL[] { jarOf(e).toUri().toURL() }, Platform.class.getClassLoader(), deps);
                    final Class<?> c = Class.forName(e.manifest.entrypoint(), true, e.classLoader);
                    if (!GameModule.class.isAssignableFrom(c)) {
                        throw new IllegalStateException(e.manifest.entrypoint() + " does not implement GameModule");
                    }
                    e.instance = (GameModule) c.getDeclaredConstructor().newInstance();
                }
                e.set(ModuleState.LOADED, null);
            } catch (Throwable ex) {
                LOGGER.log(Level.WARNING, "Platform: cannot load module " + id, ex);
                e.set(ModuleState.FAILED, "load: " + ex);
            }
            saveState(e);
        }
    }

    private ModuleGuard newGuard(ModuleEntry e) {
        return new ModuleGuard(e.id(), new CircuitBreaker(settings.breakerErrors(), settings.breakerWindow().toMillis()), settings.slowCall().toNanos(), () -> e.state.isActive(), reason -> org.l2jmobius.commons.threads.ThreadPool.execute(() -> degrade(e.id(), reason)), System::currentTimeMillis);
    }

    /** A required dependency is not usable -> REFUSED. Returns true when blocked. */
    private boolean cascadeBlocked(ModuleEntry e) {
        for (Dependency d : e.manifest.dependencies()) {
            if (d.optional()) {
                continue;
            }
            final ModuleEntry de = entries.get(d.id());
            if (de == null || de.state.isError() || de.state == ModuleState.DEGRADED || de.state == ModuleState.DISABLED) {
                e.set(ModuleState.REFUSED, "depends on " + (de == null ? "missing" : de.state.name().toLowerCase()) + " module " + d.id());
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ data hooks (P1-DATA-*, P1-HTML)

    /**
     * Folders of active modules for one data type, called by the stock loaders right after they parsed the stock
     * folders. {@code exists} tells whether an id is already defined (stock data); a module that would overwrite stock
     * data is marked FAILED during startup and its folder is skipped.
     */
    public List<File> dataDirs(String typeKey, IntPredicate exists) {
        if (!active) {
            return List.of();
        }
        final DataType type = DataType.byKey(typeKey);
        if (type == null) {
            return List.of();
        }
        final List<File> out = new ArrayList<>();
        synchronized (this) {
            for (String id : order) {
                final ModuleEntry e = entries.get(id);
                if (!e.state.isActive() || e.dataDir(type) == null) {
                    continue;
                }
                if (exists != null && type.hasIds()) {
                    final Set<Integer> already = e.loadedIds.getOrDefault(type, Set.of());
                    final List<Integer> clash = new ArrayList<>();
                    for (int x : e.ids.getOrDefault(type, new TreeSet<>())) {
                        if (!already.contains(x) && exists.test(x)) {
                            clash.add(x);
                        }
                    }
                    if (!clash.isEmpty()) {
                        final String why = type.key() + " id " + clash + " already exists in stock data";
                        if (!started) {
                            e.set(ModuleState.FAILED, why);
                            LOGGER.warning("Platform: module " + id + " FAILED: " + why + " (its " + type.key() + " are not loaded)");
                            saveState(e);
                        } else {
                            LOGGER.warning("Platform: module " + id + ": " + why + ", " + type.key() + " folder skipped on reload");
                        }
                        continue;
                    }
                    e.loadedIds.computeIfAbsent(type, k -> new HashSet<>()).addAll(e.ids.getOrDefault(type, new TreeSet<>()));
                }
                out.add(e.dataDir(type).toFile());
            }
        }
        if (!out.isEmpty()) {
            LOGGER.info("Platform: " + type.key() + " from modules: " + out.size() + " folder(s)");
        }
        return out;
    }

    /** HTML of module pages "data/html/modules/&lt;id&gt;/..." (stock HtmCache asks only on a miss). */
    public String moduleHtml(String prefix, String path) {
        if (!active || path == null || !path.startsWith(HTML_PREFIX)) {
            return null;
        }
        final String rest = path.substring(HTML_PREFIX.length());
        final int slash = rest.indexOf('/');
        if (slash <= 0 || rest.contains("..") || rest.contains("\\")) {
            return null;
        }
        final ModuleEntry e = entries.get(rest.substring(0, slash));
        if (e == null || !e.state.isActive() || e.dataDir(DataType.HTML) == null) {
            return null;
        }
        final String file = rest.substring(slash + 1);
        final Path root = e.dataDir(DataType.HTML);
        final List<Path> candidates = new ArrayList<>();
        if (prefix != null && prefix.startsWith("data/lang/")) {
            final String lang = prefix.substring("data/lang/".length()).replace("/", "");
            if (!lang.isEmpty()) {
                candidates.add(root.resolve("lang").resolve(lang).resolve(file));
            }
        }
        candidates.add(root.resolve(file));
        for (Path p : candidates) {
            final Path n = p.normalize();
            if (!n.startsWith(root)) {
                return null;
            }
            final String key = n.toString();
            final String cached = htmlCache.get(key);
            if (cached != null) {
                return cached;
            }
            if (Files.isRegularFile(n)) {
                try {
                    final String content = Files.readString(n, StandardCharsets.UTF_8).replace("\uFEFF", "");
                    htmlCache.put(key, content);
                    return content;
                } catch (IOException ex) {
                    LOGGER.warning("Platform: cannot read " + n + ": " + ex.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ enable (P1-ENABLE)

    public void enableModules() {
        if (!active) {
            return;
        }
        for (String id : orderCopy()) {
            final ModuleEntry e = entries.get(id);
            if (e.state != ModuleState.LOADED || cascadeBlocked(e)) {
                saveState(e);
                continue;
            }
            try {
                if (e.instance != null) {
                    e.instance.onEnable(e.runtime);
                }
                e.set(ModuleState.ENABLED, null);
                final int registered = e.runtime.ownership().size();
                LOGGER.info("Platform: enabled " + id + " " + e.version() + " (" + registered + " registrations, " + e.appliedMigrations + " new migrations)");
            } catch (Throwable ex) {
                final int undone = e.runtime.ownership().undoAll(LOGGER::warning);
                LOGGER.log(Level.WARNING, "Platform: module " + id + " failed in onEnable, " + undone + " registrations rolled back", ex);
                e.set(ModuleState.FAILED, "onEnable: " + ex);
                cascadeFailure(id);
            }
            saveState(e);
        }
        AdminModules.register(this);
        checkpoint("enable");
    }

    // ------------------------------------------------------------------ ready (P1-READY)

    public void serverReady() {
        if (!active) {
            return;
        }
        for (String id : orderCopy()) {
            final ModuleEntry e = entries.get(id);
            if (e.state != ModuleState.ENABLED) {
                continue;
            }
            try {
                if (e.instance != null) {
                    e.instance.onServerReady(e.runtime);
                }
                e.set(ModuleState.RUNNING, null);
            } catch (Throwable ex) {
                final int undone = e.runtime.ownership().undoAll(LOGGER::warning);
                LOGGER.log(Level.WARNING, "Platform: module " + id + " failed in onServerReady, " + undone + " registrations rolled back", ex);
                e.set(ModuleState.FAILED, "onServerReady: " + ex);
                cascadeFailure(id);
            }
            saveState(e);
        }
        started = true;
        summary();
        checkpoint("ready");
    }

    /** Dependants of a failed module that are already enabled are rolled back and refused. */
    private void cascadeFailure(String failedId) {
        for (ModuleEntry d : entries.values()) {
            if (d.manifest == null || d.state.isError() || d.state == ModuleState.DISABLED) {
                continue;
            }
            for (Dependency dep : d.manifest.dependencies()) {
                if (!dep.optional() && dep.id().equals(failedId)) {
                    if (d.runtime != null) {
                        d.runtime.ownership().undoAll(LOGGER::warning);
                    }
                    d.set(ModuleState.REFUSED, "depends on failed module " + failedId);
                    saveState(d);
                    cascadeFailure(d.id());
                }
            }
        }
    }

    // ------------------------------------------------------------------ runtime control

    /** Circuit breaker or //module disable: unregister everything of the module (and its dependants) until restart. */
    public synchronized boolean degrade(String id, String reason) {
        final ModuleEntry e = entries.get(id);
        if (e == null || !e.state.isActive()) {
            return false;
        }
        e.set(ModuleState.DEGRADED, reason);
        final int undone = e.runtime == null ? 0 : e.runtime.ownership().undoAll(LOGGER::warning);
        LOGGER.warning("Platform: module " + id + " DEGRADED (" + undone + " registrations removed): " + reason);
        saveState(e);
        for (ModuleEntry d : entries.values()) {
            if (d.manifest != null && d.state.isActive()) {
                for (Dependency dep : d.manifest.dependencies()) {
                    if (!dep.optional() && dep.id().equals(id)) {
                        degrade(d.id(), "dependency " + id + " degraded");
                    }
                }
            }
        }
        return true;
    }

    /** //module reload: config, i18n, html cache, then onConfigReload. Returns a human readable result. */
    public synchronized String reload(String id) {
        final ModuleEntry e = entries.get(id);
        if (e == null) {
            return "no module " + id;
        }
        if (!e.state.isActive()) {
            return id + " is " + e.state + ", reload needs a running module";
        }
        final ResolvedConfig fresh;
        try {
            fresh = ResolvedConfig.load(e.manifest, e.dir, configDir, settings.profile(), env);
        } catch (ResolvedConfig.ConfigException ex) {
            return id + ": config error, old config kept: " + ex.getMessage();
        }
        final List<String> restartNeeded = new ArrayList<>();
        for (Map.Entry<String, String> kv : fresh.asMap().entrySet()) {
            final String old = e.config.asMap().get(kv.getKey());
            final var spec = e.manifest.config().get(kv.getKey());
            if (spec != null && !spec.reloadable() && old != null && !old.equals(kv.getValue())) {
                restartNeeded.add(kv.getKey());
            }
        }
        e.config = fresh;
        try {
            e.runtime.setMessages(Messages.load(e.dir.resolve("i18n"), settings.defaultLanguage()));
        } catch (IOException ex) {
            LOGGER.warning("Platform: " + id + ": cannot reload i18n: " + ex.getMessage());
        }
        final Path html = e.dataDir(DataType.HTML);
        if (html != null) {
            htmlCache.keySet().removeIf(k -> k.startsWith(html.toString()));
        }
        if (e.instance != null) {
            e.runtime.guard().run("onConfigReload", () -> e.instance.onConfigReload(e.runtime));
        }
        LOGGER.info("Platform: reloaded config of " + id + (restartNeeded.isEmpty() ? "" : ", restart needed for " + restartNeeded));
        return id + ": config, i18n and html reloaded" + (restartNeeded.isEmpty() ? "" : "; restart needed for " + restartNeeded);
    }

    // ------------------------------------------------------------------ shutdown (P1-SHUTDOWN)

    public void shutdown() {
        if (!active) {
            return;
        }
        final List<String> reverse = orderCopy();
        Collections.reverse(reverse);
        for (String id : reverse) {
            final ModuleEntry e = entries.get(id);
            if (!e.state.isActive()) {
                continue;
            }
            if (e.instance != null) {
                final Thread t = new Thread(() -> {
                    try {
                        e.instance.onShutdown(e.runtime);
                    } catch (Throwable ex) {
                        LOGGER.log(Level.WARNING, "Platform: module " + id + " failed in onShutdown", ex);
                    }
                }, "module-shutdown-" + id);
                t.setDaemon(true);
                t.start();
                try {
                    t.join(settings.shutdownTimeout().toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (t.isAlive()) {
                    LOGGER.warning("Platform: module " + id + " did not finish onShutdown within " + settings.shutdownTimeout().toSeconds() + " s");
                }
            }
            e.runtime.ownership().undoAll(LOGGER::warning);
            e.set(ModuleState.STOPPED, null);
            saveState(e);
        }
        LOGGER.info("Platform: all modules stopped.");
    }

    // ------------------------------------------------------------------ helpers

    private synchronized List<String> orderCopy() {
        return new ArrayList<>(order);
    }

    private void saveState(ModuleEntry e) {
        if (migrations == null || e.manifest == null) {
            return;
        }
        migrations.saveState(e.id(), e.version(), e.state.name(), e.reason, e.state == ModuleState.ENABLED || e.state == ModuleState.RUNNING);
    }

    public Map<ModuleState, Integer> counts() {
        final Map<ModuleState, Integer> out = new EnumMap<>(ModuleState.class);
        for (ModuleEntry e : entries()) {
            out.merge(e.state, 1, Integer::sum);
        }
        return out;
    }

    private void summary() {
        final Map<ModuleState, Integer> c = counts();
        LOGGER.info("Platform: profile=" + settings.profile() + ", " + entries.size() + " discovered: " + c.getOrDefault(ModuleState.RUNNING, 0) + " running, " + c.getOrDefault(ModuleState.FAILED, 0) + " failed, " + c.getOrDefault(ModuleState.REFUSED, 0) + " refused, " + c.getOrDefault(ModuleState.INVALID, 0) + " invalid, " + c.getOrDefault(ModuleState.DISABLED, 0) + " disabled (bootstrap " + bootstrapMillis + " ms)");
        for (ModuleEntry e : entries()) {
            LOGGER.info("Platform:   " + e.id() + " " + e.version() + " " + e.state + (e.reason == null ? "" : ": " + e.reason));
        }
    }

    /** With FailurePolicy=abort any error of a module that is not switched off stops the server start. */
    private void checkpoint(String stage) {
        if (settings.failurePolicy() != FailurePolicy.ABORT) {
            return;
        }
        final List<String> broken = new ArrayList<>();
        for (ModuleEntry e : entries()) {
            if (e.state.isError()) {
                broken.add(e.id() + " " + e.state + ": " + e.reason);
            }
        }
        if (!broken.isEmpty()) {
            fatalIfAbort("module errors at " + stage + " stage: " + String.join(" | ", broken));
        }
    }

    private void fatalIfAbort(String why) {
        if (settings.failurePolicy() != FailurePolicy.ABORT) {
            return;
        }
        LOGGER.severe("Platform: FailurePolicy=abort (profile " + settings.profile() + "): " + why);
        LOGGER.severe("Platform: server start aborted. Fix or switch off the module (config/modules.ini) and start again.");
        exitHandler.run();
    }

    /** Replaced in tests. */
    static Runnable exitHandler = () -> {
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(1);
    };
}
