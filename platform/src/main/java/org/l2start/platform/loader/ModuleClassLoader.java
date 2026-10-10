package org.l2start.platform.loader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Class loader of one jar module. Lookup order: parent (core + platform), own jar, then class loaders of the
 * dependencies the module declared. Other modules are invisible.
 */
public final class ModuleClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String moduleId;
    private final List<ModuleClassLoader> dependencies;

    public ModuleClassLoader(String moduleId, URL[] urls, ClassLoader parent, List<ModuleClassLoader> dependencies) {
        super("module-" + moduleId, urls, parent);
        this.moduleId = moduleId;
        this.dependencies = List.copyOf(dependencies);
    }

    public String moduleId() {
        return moduleId;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = getParent().loadClass(name);
                } catch (ClassNotFoundException e) {
                    // not core/platform
                }
            }
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    // not ours
                }
            }
            if (c == null) {
                c = fromDependencies(name);
            }
            if (c == null) {
                throw new ClassNotFoundException(name + " (module " + moduleId + ")");
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    /** Own classes only, used by dependants. */
    Class<?> loadOwn(String name) {
        synchronized (getClassLoadingLock(name)) {
            final Class<?> c = findLoadedClass(name);
            if (c != null) {
                return c.getClassLoader() == this ? c : null;
            }
            try {
                return findClass(name);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }

    private Class<?> fromDependencies(String name) {
        for (ModuleClassLoader dep : dependencies) {
            final Class<?> c = dep.loadOwn(name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
