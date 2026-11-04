package org.l2start.olympiad;

import java.util.StringTokenizer;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;

/** //oly (GM): olympiad bot roster, class breakdown, waiting lists, window. Same info as //bots oly plus queues. */
final class AdminOly implements IAdminCommandHandler {
    private final BotsOlympiadModule module;

    AdminOly(BotsOlympiadModule module) {
        this.module = module;
    }

    @Override
    public String[] getCommandList() {
        return new String[] { "admin_oly" };
    }

    @Override
    public boolean onCommand(String command, Player gm) {
        final StringTokenizer st = new StringTokenizer(command);
        st.nextToken();
        if (st.hasMoreTokens() && "points".equalsIgnoreCase(st.nextToken())) {
            Integer n = null;
            if (st.hasMoreTokens()) {
                try {
                    n = Math.max(0, Integer.parseInt(st.nextToken()));
                } catch (NumberFormatException e) {
                    gm.sendMessage("Usage: //oly points [n]");
                    return true;
                }
            }
            gm.sendMessage(module.resetPointsNow(n));
            return true;
        }
        for (String line : module.diag()) {
            gm.sendMessage(line);
        }
        return true;
    }
}
