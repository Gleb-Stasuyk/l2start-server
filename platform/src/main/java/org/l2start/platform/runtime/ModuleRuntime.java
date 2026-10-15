package org.l2start.platform.runtime;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.MultilingualSupportConfig;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.handler.AdminCommandHandler;
import org.l2jmobius.gameserver.handler.BypassHandler;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.ListenersContainer;
import org.l2jmobius.gameserver.mechanics.events.holders.IBaseEvent;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2start.platform.api.ModuleConfig;
import org.l2start.platform.api.ModuleContext;
import org.l2start.platform.api.ModuleDatabase;
import org.l2start.platform.api.ModuleEvents;
import org.l2start.platform.api.ModuleHandlers;
import org.l2start.platform.api.ModuleI18n;
import org.l2start.platform.api.ModuleInfo;
import org.l2start.platform.api.ModuleLog;
import org.l2start.platform.api.ModuleMetrics;
import org.l2start.platform.api.ModuleResources;
import org.l2start.platform.api.ModuleScheduler;
import org.l2start.platform.api.ServiceRegistry;
import org.l2start.platform.loader.ManifestReader;

/** ModuleContext of one module. All registrations go through {@link #ownership}. */
public final class ModuleRuntime implements ModuleContext {
    private final ModuleEntry entry;
    private final String profile;
    final Ownership ownership = new Ownership();
    final ModuleGuard guard;
    private final ServiceDirectory services;
    private final AtomicReference<Messages> messages = new AtomicReference<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Logger logger;

    private final ModuleInfo info = new Info();
    private final ModuleLog log = new Log();
    private final ModuleEvents events = new Events();
    private final ModuleHandlers handlers = new Handlers();
    private final ModuleScheduler scheduler = new Scheduler();
    private final ModuleDatabase db = new Db();
    private final ModuleResources resources = new Resources();
    private final ModuleI18n i18n = new I18n();
    private final ServiceRegistry serviceRegistry = new Services();
    private final ModuleMetrics metrics = new Metrics();

    public ModuleRuntime(ModuleEntry entry, String profile, ModuleGuard guard, ServiceDirectory services, Messages messages) {
        this.entry = entry;
        this.profile = profile;
        this.guard = guard;
        this.services = services;
        this.messages.set(messages);
        this.logger = Logger.getLogger("module." + entry.id());
    }

    public Ownership ownership() {
        return ownership;
    }

    public ModuleGuard guard() {
        return guard;
    }

    public void setMessages(Messages m) {
        messages.set(m);
    }

    @Override public ModuleInfo self() { return info; }
    @Override public ModuleLog log() { return log; }
    @Override public ModuleConfig config() { return entry.config; }
    @Override public ModuleEvents events() { return events; }
    @Override public ModuleHandlers handlers() { return handlers; }
    @Override public ModuleScheduler scheduler() { return scheduler; }
    @Override public ModuleDatabase db() { return db; }
    @Override public ModuleResources resources() { return resources; }
    @Override public ModuleI18n i18n() { return i18n; }
    @Override public ServiceRegistry services() { return serviceRegistry; }
    @Override public ModuleMetrics metrics() { return metrics; }

    @Override
    public void botPresence(org.l2start.platform.api.BotPresence presence) {
        java.util.Objects.requireNonNull(presence, "presence");
        org.l2start.platform.hooks.BotHooks.install(entry.id(), presence);
        ownership.add("botPresence", entry.id(), () -> org.l2start.platform.hooks.BotHooks.uninstall(entry.id()));
    }

    @Override
    public void onStoreDeal(org.l2start.platform.api.StoreDealListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        final org.l2start.platform.api.StoreDealListener guarded = d -> guard.run("store deal", () -> listener.onDeal(d));
        org.l2start.platform.hooks.StoreHooks.add(entry.id(), guarded);
        ownership.add("storeDeal", entry.id(), () -> org.l2start.platform.hooks.StoreHooks.remove(entry.id(), guarded));
    }

    @Override
    public void onDrops(org.l2start.platform.api.DropListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        final org.l2start.platform.api.DropListener guarded = (spoil, victim, killer, roll) -> {
            final Object[] out = { null };
            guard.run("drops", () -> out[0] = listener.onDrops(spoil, victim, killer, roll));
            @SuppressWarnings("unchecked")
            final java.util.List<org.l2jmobius.gameserver.entity.item.holders.ItemHolder> list = (java.util.List<org.l2jmobius.gameserver.entity.item.holders.ItemHolder>) out[0];
            return list;
        };
        org.l2start.platform.hooks.DropHooks.addDrop(entry.id(), guarded);
        ownership.add("drops", entry.id(), () -> org.l2start.platform.hooks.DropHooks.removeDrop(entry.id(), guarded));
    }

    @Override
    public void onTeleportName(org.l2start.platform.api.TeleportNameListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        final org.l2start.platform.api.TeleportNameListener guarded = (p, where, name) -> guard.call("teleport name", () -> listener.onTeleportName(p, where, name), name);
        org.l2start.platform.hooks.DropHooks.addName(entry.id(), guarded);
        ownership.add("teleportName", entry.id(), () -> org.l2start.platform.hooks.DropHooks.removeName(entry.id(), guarded));
    }

    @Override
    public void onQuestReward(org.l2start.platform.api.QuestRewardListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        final org.l2start.platform.api.QuestRewardListener guarded = (p, id, base, rated) -> {
            final long[] out = { rated };
            guard.run("quest reward", () -> out[0] = listener.onQuestReward(p, id, base, rated));
            return out[0];
        };
        org.l2start.platform.hooks.DropHooks.addQuest(entry.id(), guarded);
        ownership.add("questReward", entry.id(), () -> org.l2start.platform.hooks.DropHooks.removeQuest(entry.id(), guarded));
    }

    @Override
    public void onBotTradeRequest(org.l2start.platform.api.BotTradeListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        final org.l2start.platform.api.BotTradeListener guarded = (r, b) -> {
            final boolean[] out = { false };
            guard.run("bot trade request", () -> out[0] = listener.onTradeRequest(r, b));
            return out[0];
        };
        org.l2start.platform.hooks.TradeHooks.add(entry.id(), guarded);
        ownership.add("botTrade", entry.id(), () -> org.l2start.platform.hooks.TradeHooks.remove(entry.id(), guarded));
    }

    @Override
    public void onBotPartyRequest(org.l2start.platform.api.BotPartyListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        final org.l2start.platform.api.BotPartyListener guarded = new org.l2start.platform.api.BotPartyListener() {
            @Override
            public boolean onPartyRequest(org.l2jmobius.gameserver.entity.actor.Player r, org.l2jmobius.gameserver.entity.actor.Player b) {
                final boolean[] out = { false };
                guard.run("bot party request", () -> out[0] = listener.onPartyRequest(r, b));
                return out[0];
            }

            @Override
            public boolean onPartyBusy(org.l2jmobius.gameserver.entity.actor.Player r, org.l2jmobius.gameserver.entity.actor.Player b) {
                final boolean[] out = { false };
                guard.run("bot party busy", () -> out[0] = listener.onPartyBusy(r, b));
                return out[0];
            }
        };
        org.l2start.platform.hooks.PartyHooks.add(entry.id(), guarded);
        ownership.add("botParty", entry.id(), () -> org.l2start.platform.hooks.PartyHooks.remove(entry.id(), guarded));
    }

    @Override
    public void cleanup(String name, Runnable action) {
        java.util.Objects.requireNonNull(action, "action");
        ownership.add("cleanup", name, action);
    }

    private String prefix() {
        return "Module[" + entry.id() + "]: ";
    }

    private final class Info implements ModuleInfo {
        @Override public String id() { return entry.id(); }
        @Override public String name() { return entry.manifest.name(); }
        @Override public String version() { return entry.version(); }
        @Override public String profile() { return profile; }
        @Override public Path root() { return entry.dir; }
    }

    private final class Log implements ModuleLog {
        @Override public void info(String message) { logger.info(prefix() + message); }
        @Override public void warn(String message) { logger.warning(prefix() + message); }
        @Override public void warn(String message, Throwable t) { logger.log(Level.WARNING, prefix() + message, t); }
        @Override public void error(String message, Throwable t) { logger.log(Level.SEVERE, prefix() + message, t); }
        @Override public void debug(String message) { logger.fine(prefix() + message); }
    }

    private final class Events implements ModuleEvents {
        @Override public <T extends IBaseEvent> void onGlobal(EventType type, Consumer<T> cb) { on(Containers.Global(), type, cb); }
        @Override public <T extends IBaseEvent> void onPlayers(EventType type, Consumer<T> cb) { on(Containers.Players(), type, cb); }
        @Override public <T extends IBaseEvent> void onNpcs(EventType type, Consumer<T> cb) { on(Containers.Npcs(), type, cb); }
        @Override public <T extends IBaseEvent> void onMonsters(EventType type, Consumer<T> cb) { on(Containers.Monsters(), type, cb); }

        @Override
        public <T extends IBaseEvent> void onNpc(int npcId, EventType type, Consumer<T> cb) {
            final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
            if (template == null) {
                throw new IllegalArgumentException("NPC template " + npcId + " does not exist");
            }
            on(template, type, cb);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends IBaseEvent> void on(ListenersContainer container, EventType type, Consumer<T> cb) {
            final String point = "event " + type.name();
            final Consumer<IBaseEvent> guarded = e -> guard.run(point, () -> cb.accept((T) e));
            final ConsumerEventListener listener = new ConsumerEventListener(container, type, guarded, ModuleRuntime.this);
            container.addListener(listener);
            ownership.add("listener", type.name(), () -> container.removeListener(listener));
        }
    }

    private final class Handlers implements ModuleHandlers {
        @Override
        public void admin(IAdminCommandHandler handler) {
            final AdminCommandHandler reg = AdminCommandHandler.getInstance();
            for (String c : handler.getCommandList()) {
                if (reg.getHandler(c) != null) {
                    throw new IllegalStateException("admin command " + c + " is already registered");
                }
            }
            final IAdminCommandHandler wrapped = new IAdminCommandHandler() {
                @Override
                public boolean onCommand(String command, Player player) {
                    final Boolean r = guard.call("admin " + command.split(" ")[0], () -> handler.onCommand(command, player), Boolean.FALSE);
                    return (r != null) && r;
                }

                @Override
                public String[] getCommandList() {
                    return handler.getCommandList();
                }
            };
            reg.registerHandler(wrapped);
            ownership.add("admin", String.join(",", handler.getCommandList()), () -> reg.removeHandler(wrapped));
        }

        @Override
        public void voiced(IVoicedCommandHandler handler) {
            final VoicedCommandHandler reg = VoicedCommandHandler.getInstance();
            for (String c : handler.getCommandList()) {
                if (reg.getHandler(c) != null) {
                    throw new IllegalStateException("voiced command ." + c + " is already registered");
                }
            }
            final IVoicedCommandHandler wrapped = new IVoicedCommandHandler() {
                @Override
                public boolean onCommand(String command, Player player, String params) {
                    final Boolean r = guard.call("voiced ." + command, () -> handler.onCommand(command, player, params), null);
                    if (r == null) {
                        player.sendMessage("Command failed (module " + entry.id() + ").");
                        return true;
                    }
                    return r;
                }

                @Override
                public String[] getCommandList() {
                    return handler.getCommandList();
                }
            };
            reg.registerHandler(wrapped);
            ownership.add("voiced", String.join(",", handler.getCommandList()), () -> reg.removeHandler(wrapped));
        }

        @Override
        public void bypass(IBypassHandler handler) {
            final BypassHandler reg = BypassHandler.getInstance();
            for (String c : handler.getCommandList()) {
                if (reg.getHandler(c) != null) {
                    throw new IllegalStateException("bypass " + c + " is already registered");
                }
            }
            final IBypassHandler wrapped = new IBypassHandler() {
                @Override
                public boolean onCommand(String command, Player player, Creature origin) {
                    final Boolean r = guard.call("bypass " + command.split(" ")[0], () -> handler.onCommand(command, player, origin), Boolean.FALSE);
                    return (r != null) && r;
                }

                @Override
                public String[] getCommandList() {
                    return handler.getCommandList();
                }
            };
            reg.registerHandler(wrapped);
            ownership.add("bypass", String.join(",", handler.getCommandList()), () -> reg.removeHandler(wrapped));
        }

        @Override
        public void wrapBypass(String command, java.util.function.UnaryOperator<IBypassHandler> wrapper) {
            final BypassHandler reg = BypassHandler.getInstance();
            final IBypassHandler previous = reg.getHandler(command);
            if (previous == null) {
                throw new IllegalStateException("bypass " + command + " is not registered, nothing to wrap");
            }
            final IBypassHandler mine = java.util.Objects.requireNonNull(wrapper.apply(previous), "wrapper result");
            final IBypassHandler wrapped = new IBypassHandler() {
                @Override
                public boolean onCommand(String cmd, Player player, Creature origin) {
                    final Boolean r = guard.call("bypass " + command, () -> mine.onCommand(cmd, player, origin), null);
                    return (r != null) ? r : previous.onCommand(cmd, player, origin);
                }

                @Override
                public String[] getCommandList() {
                    return new String[] { command };
                }
            };
            reg.registerHandler(wrapped);
            ownership.add("bypass-wrap", command, () -> {
                if (reg.getHandler(command) == wrapped) {
                    reg.registerHandler(previous); // its other commands point to it already
                }
            });
        }
    }

    private final class Scheduler implements ModuleScheduler {
        @Override
        public ScheduledFuture<?> schedule(String name, Duration delay, Runnable task) {
            final AtomicReference<Ownership.Registration> reg = new AtomicReference<>();
            final ScheduledFuture<?> f = ThreadPool.schedule(() -> {
                try {
                    guard.run("task " + name, task::run);
                } finally {
                    final Ownership.Registration r = reg.get();
                    if (r != null) {
                        ownership.forget(r);
                    }
                }
            }, delay.toMillis());
            reg.set(ownership.add("task", name, () -> f.cancel(false)));
            if (f.isDone()) {
                ownership.forget(reg.get());
            }
            return f;
        }

        @Override
        public ScheduledFuture<?> fixedRate(String name, Duration initialDelay, Duration period, Runnable task) {
            final ScheduledFuture<?> f = ThreadPool.scheduleAtFixedRate(() -> guard.run("task " + name, task::run), initialDelay.toMillis(), period.toMillis());
            ownership.add("task", name, () -> f.cancel(false));
            return f;
        }
    }

    private final class Db implements ModuleDatabase {
        @Override
        public Connection connection() throws SQLException {
            return DatabaseFactory.getConnection();
        }

        @Override
        public String table(String name) {
            return "mod_" + entry.id().replace('-', '_') + "_" + name;
        }

        @Override
        public int update(String sql, Object... params) throws SQLException {
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
            final List<T> out = new ArrayList<>();
            try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                }
            }
            return out;
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
            final List<T> l = query(sql, mapper, params);
            return l.isEmpty() ? Optional.empty() : Optional.ofNullable(l.get(0));
        }

        private void bind(PreparedStatement ps, Object[] params) throws SQLException {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }

    private final class Resources implements ModuleResources {
        @Override
        public Path root() {
            return entry.dir;
        }

        @Override
        public Path path(String relative) {
            final List<String> errors = new ArrayList<>();
            final Path p = ManifestReader.safePath(entry.dir, relative, "path", errors);
            if (p == null) {
                throw new IllegalArgumentException(errors.get(0));
            }
            return p;
        }

        @Override
        public String html(String relative) {
            if (relative.contains("..") || relative.startsWith("/")) {
                throw new IllegalArgumentException("bad html path " + relative);
            }
            return "data/html/modules/" + entry.id() + "/" + relative;
        }
    }

    private final class I18n implements ModuleI18n {
        @Override
        public String get(Player player, String key, Object... args) {
            return messages.get().get(lang(player), key, args);
        }

        @Override
        public String get(String lang, String key, Object... args) {
            return messages.get().get(lang, key, args);
        }

        @Override
        public String lang(Player player) {
            if ((player != null) && MultilingualSupportConfig.MULTILANG_ENABLE && (player.getLang() != null)) {
                return player.getLang();
            }
            return messages.get().defaultLanguage();
        }
    }

    private final class Services implements ServiceRegistry {
        @Override
        public <T> void provide(Class<T> type, T implementation) {
            services.provide(entry.id(), type, implementation);
            ownership.add("service", type.getSimpleName(), () -> services.remove(entry.id(), type));
        }

        @Override
        public <T> T require(Class<T> type) {
            return find(type).orElseThrow(() -> new IllegalStateException("service " + type.getName() + " is not provided by this module or its dependencies"));
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            return services.find(entry.id(), entry.visibleDependencies, type);
        }
    }

    private final class Metrics implements ModuleMetrics {
        @Override
        public Counter counter(String name) {
            final AtomicLong c = counters.computeIfAbsent(name, k -> new AtomicLong());
            return new Counter() {
                @Override public void inc() { c.incrementAndGet(); }
                @Override public void add(long n) { c.addAndGet(n); }
                @Override public long get() { return c.get(); }
            };
        }

        @Override
        public Map<String, Long> snapshot() {
            final Map<String, Long> out = new TreeMap<>();
            counters.forEach((k, v) -> out.put(k, v.get()));
            return out;
        }
    }
}
