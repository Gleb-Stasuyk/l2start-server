package org.l2start.platform.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleClassLoaderTest {
    @TempDir
    Path tmp;

    /** Compiles one source into a jar. */
    private Path jar(String name, String className, String source, Path... classpath) throws Exception {
        final Path src = tmp.resolve(name + "-src/" + className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        final Path out = tmp.resolve(name + "-classes");
        Files.createDirectories(out);
        final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        final StringBuilder cp = new StringBuilder(Path.of(org.l2start.platform.util.Semver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        for (Path p : classpath) {
            cp.append(java.io.File.pathSeparator).append(p);
        }
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), "-cp", cp.toString(), src.toString()));
        final Path jar = tmp.resolve(name + ".jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os); Stream<Path> files = Files.walk(out)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(out.relativize(f).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(f));
                jos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void seesParentOwnAndDeclaredDependenciesOnly() throws Exception {
        final Path base = jar("base", "m.base.Api", "package m.base; public class Api { public static String hi() { return \"base\"; } }");
        final Path other = jar("other", "m.other.Secret", "package m.other; public class Secret { }");
        final Path app = jar("app", "m.app.Main", "package m.app; public class Main implements java.util.function.Supplier<String> { public String get() { return m.base.Api.hi() + \"+app+\" + org.l2start.platform.util.Semver.parse(\"1.2.3\"); } }", base);

        final ClassLoader parent = getClass().getClassLoader();
        final ModuleClassLoader baseCl = new ModuleClassLoader("base", new URL[] { base.toUri().toURL() }, parent, List.of());
        final ModuleClassLoader otherCl = new ModuleClassLoader("other", new URL[] { other.toUri().toURL() }, parent, List.of());
        final ModuleClassLoader appCl = new ModuleClassLoader("app", new URL[] { app.toUri().toURL() }, parent, List.of(baseCl));

        @SuppressWarnings("unchecked")
        final java.util.function.Supplier<String> main = (java.util.function.Supplier<String>) appCl.loadClass("m.app.Main").getDeclaredConstructor().newInstance();
        assertEquals("base+app+1.2.3", main.get());
        // Same Api class object as the dependency (no duplicate copies).
        assertTrue(appCl.loadClass("m.base.Api") == baseCl.loadClass("m.base.Api"));
        // Platform class comes from the parent.
        assertTrue(appCl.loadClass("org.l2start.platform.util.Semver") == org.l2start.platform.util.Semver.class);
        // Undeclared module is invisible.
        assertNotNull(otherCl.loadClass("m.other.Secret"));
        assertThrows(ClassNotFoundException.class, () -> appCl.loadClass("m.other.Secret"));
        // A dependency cannot see its dependant.
        assertThrows(ClassNotFoundException.class, () -> baseCl.loadClass("m.app.Main"));
        appCl.close();
        baseCl.close();
        otherCl.close();
    }
}
