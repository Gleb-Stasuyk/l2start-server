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
}
