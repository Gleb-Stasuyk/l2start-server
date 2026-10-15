package org.l2start.bots.testkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Brain;
import org.l2start.bots.api.BrainContext;
import org.l2start.bots.api.WorldView;
import org.l2start.bots.runtime.Lod;

/**
 * Runs a brain against a {@link FakeWorld} on a {@link ManualClock}, ticking it like the runtime does (ACTIVE LOD:
 * at least 300 ms in combat, 1 s otherwise). Hours of play take seconds.
 */
public final class BrainHarness {
    private final Brain brain;
    private final BotInfo info;
    private final FakeWorld world;
    private final ManualClock clock;
    private final RecordingActuators act;
    private final Random rnd;
    private final Map<String, Object> blackboard = new HashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> states = new ArrayList<>();
    private long nextTick;
    private long returnAfterDeathMs = 20_000;
    private long deadSince = -1;
    private long ticks;
    private WorldView view;

    public BrainHarness(Brain brain, BotInfo info, FakeWorld world, long seed) {
        this.brain = brain;
        this.info = info;
        this.world = world;
        clock = new ManualClock(world.now());
        act = new RecordingActuators(world.actuators());
        rnd = new Random(seed);
    }

    private final BrainContext ctx = new BrainContext() {
        @Override public BotInfo info() { return info; }
        @Override public WorldView world() { return view; }
        @Override public Actuators act() { return act; }
        @Override public Random rnd() { return rnd; }
        @Override public long now() { return clock.now(); }
        @Override public Map<String, Object> blackboard() { return blackboard; }
        @Override public long reactionDelay() { return Math.max(200, Math.min(1200, Math.round(info.reactionMs() * (0.7 + (rnd.nextDouble() * 0.6))))); }
        @Override public void note(String text) { notes.add(clock.now() / 1000 + "s " + text); }
        @Override public org.l2start.bots.api.Assignment assignment() { return assignment; }
        @Override public org.l2start.bots.api.Memory memory() { return memory; }
        @Override public org.l2start.bots.api.Chat chat() { if (chat == null) { chat = defaultChat(); } return chat; }
        @Override public org.l2start.bots.chat.SocialBoard social() { return social; }
    };

    /** Phase 6: the real phrase banks of bots-core when the test runs in the repository, else silent. */
    private org.l2start.bots.api.Chat chat;

    private org.l2start.bots.api.Chat defaultChat() {
        final java.nio.file.Path p = java.nio.file.Path.of("modules/bots-core/data/chat/phrases.xml");
        if (!java.nio.file.Files.exists(p)) {
            return org.l2start.bots.api.Chat.SILENT;
        }
        try {
            return new org.l2start.bots.chat.TemplateChat(new org.l2start.bots.chat.TemplateBrain(org.l2start.bots.chat.Phrases.load(p), () -> clock.now(), rnd), info, rnd);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void setChat(org.l2start.bots.api.Chat c) { chat = c; }

    private org.l2start.bots.chat.SocialBoard social = new org.l2start.bots.chat.SocialBoard(60);

    /** Phase 6d: shared LFP shout/reply board (tests get a generous limit by default). */
    public void setSocial(org.l2start.bots.chat.SocialBoard board) { social = board == null ? org.l2start.bots.chat.SocialBoard.NONE : board; }

    public org.l2start.bots.chat.SocialBoard social() { return social; }

    private final org.l2start.bots.api.Memory.MapMemory memory = new org.l2start.bots.api.Memory.MapMemory(this::now);

    private long now() { return clock.now(); }

    /** Phase 6: the bot's memory of players (in memory only). */
    public org.l2start.bots.api.Memory.MapMemory memory() { return memory; }

    private volatile org.l2start.bots.api.Assignment assignment;

    /** The presence director's order (null = none, manual mode). */
    public void setAssignment(org.l2start.bots.api.Assignment a) { assignment = a; }

    /** Run for {@code ms} of game time. */
    public void run(long ms) {
        final long end = clock.now() + ms;
        while (clock.now() < end) {
            if (clock.now() >= nextTick) {
                view = world.view();
                final long wanted = brain.tick(ctx);
                ticks++;
                final String s = brain.state();
                if (states.isEmpty() || !states.get(states.size() - 1).equals(s)) {
                    states.add(s);
                }
                nextTick = clock.now() + Lod.delay(Lod.Level.ACTIVE, brain.inCombat(), wanted, 0);
            }
            final long step = Math.max(1, Math.min(nextTick, end) - clock.now());
            clock.advance(step);
            world.runUntil(clock.now());
            // what bots-core does: a dead bot goes back to town after a while
            if (world.dead()) {
                if (deadSince < 0) {
                    deadSince = clock.now();
                } else if ((returnAfterDeathMs > 0) && ((clock.now() - deadSince) >= returnAfterDeathMs)) {
                    world.actuators().returnToTown();
                }
            } else {
                deadSince = -1;
            }
        }
    }

    /** Like bots-core DeathReturnMin/Max (0 = never: the test does it). */
    public void setReturnAfterDeath(long ms) { returnAfterDeathMs = ms; }
    public Brain brain() { return brain; }
    public RecordingActuators act() { return act; }
    public List<String> notes() { return notes; }
    /** State changes in order (consecutive duplicates removed). */
    public List<String> states() { return states; }
    public long ticks() { return ticks; }
    public BrainContext context() { return ctx; }
    public ManualClock clock() { return clock; }
}
