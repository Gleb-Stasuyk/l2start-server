package org.l2start.bots.runtime;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.l2start.bots.api.Brain;

/** Runtime state of one bot in the world. Changed only by its shard (except the mailbox and {@link #wake}). */
public final class BotSlot {
    public static final long SAFE_IDLE_MS = 60_000;
    public static final int MAX_ERRORS = 10;
    public static final long ERROR_WINDOW_MS = 600_000;

    private final int botId;
    private final String name;
    private final Object owner;
    private final Mailbox mailbox = new Mailbox();
    private final ActionLog log = new ActionLog(10);
    private final Lod lod = new Lod();
    private final AtomicLong nextTickAt;
    private final ArrayDeque<Long> errors = new ArrayDeque<>();
    private volatile Brain brain;
    private volatile long safeIdleUntil;
    private volatile String lastError;
    private volatile long lastTickNanos;
    private volatile long ticks;
    private volatile long totalErrors;
    private volatile boolean removed;

    public BotSlot(int botId, String name, Object owner, long firstTickAt) {
        this.botId = botId;
        this.name = name;
        this.owner = owner;
        nextTickAt = new AtomicLong(firstTickAt);
    }

    public int botId() { return botId; }
    public String name() { return name; }
    /** What the host keeps for this bot (bots-core: its BotRecord). */
    public Object owner() { return owner; }
    public Mailbox mailbox() { return mailbox; }
    public ActionLog log() { return log; }
    public Lod lod() { return lod; }
    public Brain brain() { return brain; }
    public void setBrain(Brain b) { brain = b; }
    public long nextTickAt() { return nextTickAt.get(); }
    public void setNextTickAt(long t) { nextTickAt.set(t); }
    public long safeIdleUntil() { return safeIdleUntil; }
    public String lastError() { return lastError; }
    public long lastTickNanos() { return lastTickNanos; }
    public long ticks() { return ticks; }
    public long totalErrors() { return totalErrors; }
    public boolean removed() { return removed; }
    public void markRemoved() { removed = true; }

    /** An event arrived: tick no later than {@code at} (a safe-idle bot keeps sleeping). */
    public void wake(long at) {
        nextTickAt.accumulateAndGet(Math.max(at, safeIdleUntil), Math::min);
    }

    void ticked(long nanos) {
        lastTickNanos = nanos;
        ticks++;
    }

    /** @return true when this was error number {@link #MAX_ERRORS} within {@link #ERROR_WINDOW_MS} */
    boolean failed(long now, Throwable t) {
        totalErrors++;
        lastError = t.toString();
        errors.addLast(now);
        while (!errors.isEmpty() && ((now - errors.peekFirst()) > ERROR_WINDOW_MS)) {
            errors.pollFirst();
        }
        safeIdleUntil = now + SAFE_IDLE_MS;
        nextTickAt.set(safeIdleUntil);
        return errors.size() >= MAX_ERRORS;
    }

    public boolean safeIdle(long now) {
        return now < safeIdleUntil;
    }

    @Override
    public String toString() {
        return "BotSlot[" + botId + " " + name + "]";
    }
}
