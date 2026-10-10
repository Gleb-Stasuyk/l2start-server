package org.l2start.bots.core;

import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2start.bots.persona.Persona;

/** One bot: its stock character and account (persistent), plus what is going on right now (runtime). */
public final class BotRecord {
    public enum Status {
        ACTIVE, RETIRED
    }

    private final int botId;
    private final int charId;
    private final String accountName;
    private volatile String name;
    private final int classId;
    private volatile Status status;
    private volatile Persona persona;
    private volatile int homeX, homeY, homeZ;
    private volatile String goal = "IDLE";
    /** Was in the world when the server (or the module) stopped: brought back on the next start. */
    private volatile boolean restore;

    // runtime
    private volatile Player player;
    private volatile long inWorldSince;
    private volatile long lastLogin;
    private volatile long lastLogout;
    private final AtomicInteger whispers = new AtomicInteger();
    private volatile String lastWhisper;
    private final AtomicInteger deaths = new AtomicInteger();
    private volatile org.l2start.bots.runtime.BotSlot slot;
    private volatile String trouble;

    public BotRecord(int botId, int charId, String accountName, String name, int classId, Status status, long lastLogin, long lastLogout) {
        this.botId = botId;
        this.charId = charId;
        this.accountName = accountName;
        this.name = name;
        this.classId = classId;
        this.status = status;
        this.lastLogin = lastLogin;
        this.lastLogout = lastLogout;
    }

    public int botId() { return botId; }
    public int charId() { return charId; }
    public String accountName() { return accountName; }
    public String name() { return name; }
    public int classId() { return classId; }
    public Status status() { return status; }
    public Player player() { return player; }
    public boolean inWorld() { return player != null; }
    public long inWorldSince() { return inWorldSince; }
    public long lastLogin() { return lastLogin; }
    public long lastLogout() { return lastLogout; }
    public int whispers() { return whispers.get(); }
    public String lastWhisper() { return lastWhisper; }
    public int deaths() { return deaths.get(); }
    public Persona persona() { return persona; }
    public int homeX() { return homeX; }
    public int homeY() { return homeY; }
    public int homeZ() { return homeZ; }
    public String goal() { return goal; }
    public boolean restore() { return restore; }
    /** Runtime state while in the world (null = not ticking). */
    public org.l2start.bots.runtime.BotSlot slot() { return slot; }
    /** Why the runtime took it out of the world last time (null = nothing). */
    public String trouble() { return trouble; }

    void setSlot(org.l2start.bots.runtime.BotSlot s) { slot = s; }
    void setTrouble(String t) { trouble = t; }

    void setPersona(Persona p) { persona = p; }
    void setHome(int x, int y, int z) { homeX = x; homeY = y; homeZ = z; }
    void setGoal(String g) { goal = g; }
    void setRestore(boolean r) { restore = r; }

    void setName(String n) { name = n; }
    void setStatus(Status s) { status = s; }

    void enteredWorld(Player p, long now) {
        player = p;
        inWorldSince = now;
        lastLogin = now;
    }

    void leftWorld(long now) {
        player = null;
        inWorldSince = 0;
        lastLogout = now;
    }

    void whisper(String from, String text) {
        whispers.incrementAndGet();
        lastWhisper = from + ": " + text;
    }

    void died() {
        deaths.incrementAndGet();
    }

    @Override
    public String toString() {
        return "Bot[" + botId + " " + name + " char=" + charId + (inWorld() ? " in world" : "") + "]";
    }
}
