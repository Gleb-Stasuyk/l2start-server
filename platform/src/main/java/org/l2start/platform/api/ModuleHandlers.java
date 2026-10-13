package org.l2start.platform.api;

import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

/**
 * Command handlers. A command already registered by the stock server or another module is a registration error
 * (the stock handler is never overwritten). Admin commands without an AdminCommands.xml entry are available to the
 * highest access level (stock behaviour).
 */
public interface ModuleHandlers {
    void admin(IAdminCommandHandler handler);

    void voiced(IVoicedCommandHandler handler);

    void bypass(IBypassHandler handler);

    /**
     * Since API 1.7. Wraps the handler already registered for {@code command} (stock or another module): the wrapper gets
     * the current handler and returns the one to use (usually delegating what it does not handle). Guarded: when the
     * wrapper fails, the previous handler answers. Restored when the module is disabled.
     */
    default void wrapBypass(String command, java.util.function.UnaryOperator<IBypassHandler> wrapper) {
        throw new UnsupportedOperationException("wrapBypass needs platform API 1.7");
    }
}
