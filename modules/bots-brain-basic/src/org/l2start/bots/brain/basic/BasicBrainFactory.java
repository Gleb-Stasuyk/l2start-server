package org.l2start.bots.brain.basic;

import java.util.List;

import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Brain;
import org.l2start.bots.api.BrainFactory;

/** Brain id "basic". */
public final class BasicBrainFactory implements BrainFactory {
    public static final String ID = "basic";

    private final LevelZones zones;
    private final List<Village> villages;
    private final MobClaims claims = new MobClaims();

    public BasicBrainFactory(LevelZones zones, List<Village> villages) {
        this.zones = zones;
        this.villages = villages;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Brain create(BotInfo info) {
        return new BasicBrain(info.botId(), zones, villages, claims);
    }

    public MobClaims claims() {
        return claims;
    }
}
