package org.l2start.bots.brain.basic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which bot has picked which monster, so two bots do not run for the same one (shared by all bots of the module). */
public final class MobClaims {
    private record Claim(int botId, long until) {
    }

    private final Map<Integer, Claim> claims = new ConcurrentHashMap<>();

    /** @return true when the monster is now this bot's (or already was) */
    public boolean claim(int mobId, int botId, long now, long forMs) {
        final Claim c = claims.compute(mobId, (k, old) -> (old == null) || (old.botId == botId) || (old.until < now) ? new Claim(botId, now + forMs) : old);
        return c.botId == botId;
    }

    public boolean takenByOther(int mobId, int botId, long now) {
        final Claim c = claims.get(mobId);
        return (c != null) && (c.botId != botId) && (c.until >= now);
    }

    public void release(int mobId, int botId) {
        claims.computeIfPresent(mobId, (k, old) -> old.botId == botId ? null : old);
    }

    public void releaseAll(int botId) {
        claims.values().removeIf(c -> c.botId == botId);
    }

    public int size() {
        return claims.size();
    }
}
