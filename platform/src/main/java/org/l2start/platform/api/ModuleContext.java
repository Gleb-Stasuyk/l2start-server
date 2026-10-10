package org.l2start.platform.api;

/** Everything a module may use. Registrations made through it are owned by the module and undone on disable. */
public interface ModuleContext {
    ModuleInfo self();

    ModuleLog log();

    ModuleConfig config();

    ModuleEvents events();

    ModuleHandlers handlers();

    ModuleScheduler scheduler();

    ModuleDatabase db();

    ModuleResources resources();

    ModuleI18n i18n();

    ServiceRegistry services();

    ModuleMetrics metrics();
}
