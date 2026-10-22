package org.l2start.bots.clan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2start.bots.chat.SocialBoard;
import org.l2start.bots.chat.WhisperParse;
import org.l2start.bots.core.BotRecord;
import org.l2start.bots.core.BotRegistry;
import org.l2start.bots.core.BotWorld;
import org.l2start.platform.api.ModuleContext;
import org.l2start.platform.hooks.CoreHooks;

/**
 * Phase 7f/7g: academy recruiters near GK — invite only when asked; CH assigned to their clans.
 * Shouts are outside the 6g SocialBoard world limit (per-bot {@code AcademyShoutIntervalSec} only).
 */
public final class AcademyRecruiters {
    /** Legacy default; prefer {@link #pickTitle()}. */
    public static final String TITLE = "Набор в академ";
    public static final List<String> TITLES = List.of("Набор в академ", "LF academ");
    private static final int NEAR = 6000;
    private static final long STAY_MS = 5 * 60_000L;
    private static final long SHOUT_GAP_MIN_MS = 30_000L;
    private static final long SHOUT_GAP_MAX_MS = 100_000L;

    public record Spot(String town, int x, int y, int z) {
    }

    /** Academy recruiters (phase 7f) or clan recruiters (main clan, Dion/Giran/Aden/Goddard). */
    public enum Kind {
        ACADEMY, CLAN
    }

    public static final List<String> CLAN_TITLES = List.of("Набор в клан", "Клан ищет людей");
    /** Clan recruiters take players from this level (lower ones go to the academies). */
    public static final int CLAN_MIN_LEVEL = 20;
    /** A clan recruiter invites a player who comes this close (once per {@link #APPROACH_AGAIN_MS}). */
    static final int APPROACH = 150;
    static final long APPROACH_AGAIN_MS = 30 * 60_000L;
    /** Recruiters stand this close to their gatekeeper, in plain view of it. */
    public static final int POST_RADIUS = 200;

    /** Gatekeepers of the towns with clan recruiters. */
    public static final List<Spot> CLAN_TOWNS = List.of(
        new Spot("Dion", 15631, 142885, -2704),
        new Spot("Giran", 83396, 147904, -3404),
        new Spot("Aden", 146737, 25807, -2013),
        new Spot("Goddard", 147966, -55228, -2728));

    /** Gatekeepers of the four starter towns. */
    public static final List<Spot> TOWNS = List.of(
        new Spot("Gludin", -80752, 149776, -3044),
        new Spot("Gludio", -12736, 122816, -3114),
        new Spot("Dion", 15631, 142885, -2704),
        new Spot("Giran", 83396, 147904, -3404));

    private static final class Slot {
        final Spot spot;
        int botId;
        int clanId;
        long seenAt;
        long lastShoutAt;
        long nextShoutGapMs;

        Slot(Spot spot) {
            this.spot = spot;
        }
    }

    private final ModuleContext ctx;
    private final ClanService clans;
    private final BotRegistry registry;
    private final BotWorld world;
    private final Map<String, List<Slot>> byTown = new ConcurrentHashMap<>();
    private final Set<Integer> activeBotIds = ConcurrentHashMap.newKeySet();
    /** live player objectId -> object id of the recruiter bot that invited it (for the join payout by trade) */
    private final Map<Integer, Integer> inviters = new ConcurrentHashMap<>();
    private final Kind kind;
    private final List<Spot> spots;
    /** live player objectId -> when a clan recruiter last invited him on approach */
    private final Map<Integer, Long> approached = new ConcurrentHashMap<>();
    private volatile java.util.function.IntPredicate busyLeader = id -> false;

    /** Russian genitive of a recruiter town ("у ГК Глудио"). */
    public static String townGen(String town) {
        return switch (town) {
            case "Gludin" -> "Глудина";
            case "Gludio" -> "Глудио";
            case "Dion" -> "Диона";
            case "Giran" -> "Гирана";
            case "Aden" -> "Адена";
            case "Goddard" -> "Годдарда";
            default -> town;
        };
    }

    /** Pure: where the recruiter stands, for its shout. */
    public static String standsAt(String town) {
        return "Стою у ГК " + townGen(town) + ".";
    }

    /** Pure: the clan recruiter's shout (clan info and where it stands). */
    public static String clanShoutLine(String clanName, int clanLevel, String hallTown, int members, String town) {
        final String hall = (hallTown == null) || hallTown.isBlank() ? "" : ", КХ в " + hallTown;
        return "Клан " + clanName + " (" + clanLevel + " лвл" + hall + ", " + members + " чел.) набирает людей " + CLAN_MIN_LEVEL + "+! "
            + standsAt(town) + " Пиши в ПМ или подходи";
    }

    /** Pure: may a clan recruiter take this player. */
    public static boolean clanEligible(boolean hasClan, int level) {
        return !hasClan && (level >= CLAN_MIN_LEVEL);
    }

    /** Leaders the other recruiter kind already uses (one bot cannot stand at two gatekeepers). */
    public void setBusyLeader(java.util.function.IntPredicate busy) {
        busyLeader = busy == null ? id -> false : busy;
    }

    public Kind kind() {
        return kind;
    }

    /** Russian name of a recruiter town. */
    public static String townRu(String town) {
        return switch (town) {
            case "Gludin" -> "Глудине";
            case "Gludio" -> "Глудио";
            case "Dion" -> "Дионе";
            case "Giran" -> "Гиране";
            case "Aden" -> "Адене";
            case "Goddard" -> "Годдарде";
            default -> town;
        };
    }

    /** The clan's leader in the world at its recruiting place (gatekeeper of its town), or null. */
    public Leader leaderAtPlace(int clanId) {
        for (List<Slot> slots : byTown.values()) {
            for (Slot s : slots) {
                if ((s.clanId == clanId) && (s.botId > 0)) {
                    final BotRecord r = byBot(s.botId);
                    final Player p = r == null ? null : r.player();
                    if ((p != null) && p.isOnline()) {
                        return new Leader(p, s.spot.town(), "у гейткипера в " + townRu(s.spot.town()));
                    }
                }
            }
        }
        return null;
    }

    /** @param place where it waits, in Russian ("у гейткипера в Гиране") */
    public record Leader(Player bot, String town, String place) {
    }

    /** Object id of the recruiter bot that last invited this player into an academy, 0 = none. */
    public int inviterOf(int playerObjectId) {
        return inviters.getOrDefault(playerObjectId, 0);
    }

    @FunctionalInterface
    public interface ForceEnter {
        void request(int botId, int x, int y, int z, int preferLevel);
    }

    private volatile boolean enabled = true;
    private volatile SocialBoard social = SocialBoard.NONE;
    private volatile ForceEnter forceEnter = (id, x, y, z, lvl) -> {
    };
    private volatile ClanHallAssign halls;
    private volatile int joinReward = 50_000;
    private volatile int gradReward = 150_000;
    private volatile long shoutMinMs = SHOUT_GAP_MIN_MS;
    private volatile long shoutMaxMs = SHOUT_GAP_MAX_MS;

    public AcademyRecruiters(ModuleContext ctx, ClanService clans, BotRegistry registry, BotWorld world) {
        this(ctx, clans, registry, world, Kind.ACADEMY);
    }

    public AcademyRecruiters(ModuleContext ctx, ClanService clans, BotRegistry registry, BotWorld world, Kind kind) {
        this.ctx = ctx;
        this.clans = clans;
        this.registry = registry;
        this.world = world;
        this.kind = kind;
        this.spots = kind == Kind.CLAN ? CLAN_TOWNS : TOWNS;
        for (Spot s : spots) {
            byTown.put(s.town(), kind == Kind.CLAN ? List.of(new Slot(s)) : List.of(new Slot(s), new Slot(s)));
        }
    }

    private String title() {
        final List<String> l = kind == Kind.CLAN ? CLAN_TITLES : TITLES;
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    private boolean ownTitle(String t) {
        return kind == Kind.CLAN ? CLAN_TITLES.contains(t) : isRecruiterTitle(t);
    }

    public void configure(boolean on) {
        enabled = on;
    }

    /** AcademyShoutIntervalSec=min-max (seconds per bot between shouts). */
    public void setShoutIntervalSec(int minSec, int maxSec) {
        final int lo = Math.max(5, Math.min(minSec, maxSec));
        final int hi = Math.max(lo, Math.max(minSec, maxSec));
        shoutMinMs = lo * 1000L;
        shoutMaxMs = hi * 1000L;
    }

    public long shoutMinMs() {
        return shoutMinMs;
    }

    public long shoutMaxMs() {
        return shoutMaxMs;
    }

    public void setHalls(ClanHallAssign assign) {
        halls = assign;
    }

    public void setRewards(int join, int graduate) {
        joinReward = Math.max(0, join);
        gradReward = Math.max(0, graduate);
    }

    /** Random title ≤16 chars (client title limit). */
    public static String pickTitle() {
        return TITLES.get(ThreadLocalRandom.current().nextInt(TITLES.size()));
    }

    static boolean isRecruiterTitle(String title) {
        return TITLES.contains(title) || TITLE.equals(title) || "Набор в академию".equals(title);
    }

    /** Parse {@code "30-100"} → {30, 100}; bad input → defaults. */
    public static int[] parseIntervalSec(String raw) {
        if (raw == null || raw.isBlank()) {
            return new int[] { 30, 100 };
        }
        final String s = raw.trim().replace('_', '-');
        final int dash = s.indexOf('-');
        try {
            if (dash < 0) {
                final int v = Integer.parseInt(s);
                return new int[] { v, v };
            }
            final int a = Integer.parseInt(s.substring(0, dash).trim());
            final int b = Integer.parseInt(s.substring(dash + 1).trim());
            return new int[] { Math.min(a, b), Math.max(a, b) };
        } catch (NumberFormatException e) {
            return new int[] { 30, 100 };
        }
    }

    /** Clan ids currently used as recruiters (for CH assign / bids skip). */
    public Set<Integer> recruiterClanIds() {
        final Set<Integer> out = new HashSet<>();
        for (List<Slot> slots : byTown.values()) {
            for (Slot s : slots) {
                if (s.clanId > 0) {
                    out.add(s.clanId);
                }
            }
        }
        return out;
    }

    public void setSocial(SocialBoard board) {
        social = board == null ? SocialBoard.NONE : board;
    }

    /** Director force-enter for a recruiter bot id (optional). */
    public void setForceEnter(ForceEnter enter) {
        forceEnter = enter == null ? (id, x, y, z, lvl) -> {
        } : enter;
    }

    public boolean isRecruiter(int botId) {
        return activeBotIds.contains(botId);
    }

    public List<String> statusLines() {
        final List<String> out = new ArrayList<>();
        for (Spot s : spots) {
            final List<Slot> slots = byTown.get(s.town());
            int n = 0;
            final StringBuilder names = new StringBuilder();
            for (Slot slot : slots) {
                if (slot.botId <= 0) {
                    continue;
                }
                final BotRecord r = byBot(slot.botId);
                if ((r != null) && r.inWorld()) {
                    n++;
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(r.name());
                }
            }
            out.add(s.town() + ": " + n + (names.length() == 0 ? "" : " (" + names + ")"));
        }
        return out;
    }

    public void presence() {
        if (!enabled) {
            logoutAll();
            return;
        }
        final long now = System.currentTimeMillis();
        for (Spot spot : spots) {
            boolean near = false;
            for (Player p : World.getPlayers()) {
                if ((p != null) && (p.isOnlineInt() == 1) && !CoreHooks.isBot(p)
                    && (Math.hypot(p.getX() - spot.x(), p.getY() - spot.y()) <= NEAR)) {
                    near = true;
                    break;
                }
            }
            final List<Slot> slots = byTown.get(spot.town());
            if (near) {
                ensureRecruiters(spot, slots, now);
            } else {
                for (Slot slot : slots) {
                    if ((slot.botId > 0) && ((now - slot.seenAt) > STAY_MS)) {
                        logoutSlot(slot);
                    }
                }
            }
        }
    }

    public void tick() {
        if (!enabled) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (List<Slot> slots : byTown.values()) {
            for (Slot slot : slots) {
                final BotRecord r = slot.botId > 0 ? byBot(slot.botId) : null;
                final Player bot = (r != null) && r.inWorld() ? r.player() : null;
                if (bot == null) {
                    continue;
                }
                slot.seenAt = now;
                ensureTitle(bot);
                maybeShout(slot, bot, now);
                if (kind == Kind.CLAN) {
                    inviteApproaching(slot, bot, now);
                }
            }
        }
    }

    /** Whisper to a recruiter asking for academy. */
    public boolean onWhisper(Player from, BotRecord to, String text) {
        if (!enabled || (from == null) || (to == null) || !isRecruiter(to.botId())) {
            return false;
        }
        final WhisperParse.Intent intent = WhisperParse.parse(text);
        if ((intent != WhisperParse.Intent.ASK_CLAN) && !(kind == Kind.CLAN ? asksClan(text) : asksAcademy(text))) {
            return false;
        }
        final Player bot = to.player();
        if (bot == null) {
            return false;
        }
        tryInvite(from, bot, findSlot(to.botId()), System.currentTimeMillis());
        return true;
    }

    static boolean asksClan(String text) {
        if ((text == null) || text.isBlank()) {
            return false;
        }
        final String t = text.toLowerCase(Locale.ROOT);
        return t.contains("клан") || t.contains("clan") || t.contains("вступ") || t.contains("возьм") || t.contains("набор") || t.contains("take me")
            || t.contains("invite") || t.contains("инвайт") || t.contains("+");
    }

    /** Clan recruiter: a live player without a clan who comes up to it gets the invite (once per half hour). */
    private void inviteApproaching(Slot slot, Player bot, long now) {
        for (Player p : World.getVisibleObjectsInRange(bot, Player.class, APPROACH)) {
            if ((p.isOnlineInt() != 1) || CoreHooks.isBot(p) || !clanEligible(p.getClan() != null, p.getLevel())) {
                continue;
            }
            final Long at = approached.get(p.getObjectId());
            if ((at != null) && ((now - at) < APPROACH_AGAIN_MS)) {
                continue;
            }
            approached.put(p.getObjectId(), now);
            tryInvite(p, bot, slot, now);
        }
    }

    static boolean asksAcademy(String text) {
        if ((text == null) || text.isBlank()) {
            return false;
        }
        final String t = text.toLowerCase(Locale.ROOT);
        return t.contains("academy") || t.contains("академ") || t.contains("акаду") || t.contains("набор")
            || t.contains("возьмите") || t.contains("take me") || t.contains("invite me");
    }

    /** Pure: whether a recruiter may auto-invite (always false after 7g). */
    public static boolean proactiveInviteAllowed() {
        return false;
    }

    private void ensureRecruiters(Spot spot, List<Slot> slots, long now) {
        final Set<Integer> usedClans = new HashSet<>();
        for (Slot slot : slots) {
            if (slot.botId > 0) {
                final BotRecord r = byBot(slot.botId);
                if ((r != null) && r.inWorld()) {
                    slot.seenAt = now;
                    usedClans.add(slot.clanId);
                    continue;
                }
                slot.botId = 0;
                slot.clanId = 0;
            }
        }
        for (Slot slot : slots) {
            if (slot.botId > 0) {
                continue;
            }
            final ClanStore.Row row = pickClan(usedClans);
            if (row == null) {
                break;
            }
            ensureAcademy(row.clanId());
            // assign hall ownership early (no payer yet); functions enabled after place()
            if (halls != null) {
                halls.ensureForRecruiter(row.clanId(), spot.x(), spot.y(), null);
            }
            final BotRecord leader = byBot(row.leaderBotId());
            if (leader == null) {
                continue;
            }
            if (leader.inWorld()) {
                place(leader, spot, slot, row, now);
            } else {
                forceEnter.request(leader.botId(), spot.x(), spot.y(), spot.z(), 40);
                tryEnter(leader, spot, slot, row, now);
            }
            if (slot.botId > 0) {
                usedClans.add(row.clanId());
            }
        }
    }

    private ClanStore.Row pickClan(Set<Integer> used) {
        final List<ClanStore.Row> cands = new ArrayList<>();
        for (ClanStore.Row r : clans.clans()) {
            if (used.contains(r.clanId()) || (r.level() < 5) || busyLeader.test(r.leaderBotId())) {
                continue;
            }
            final BotRecord leader = byBot(r.leaderBotId());
            if ((leader != null) && (leader.persona() != null) && leader.persona().pk()) {
                continue; // no PK in towns
            }
            final Clan stock = ClanTable.getInstance().getClan(r.clanId());
            if (stock == null) {
                continue;
            }
            cands.add(r);
        }
        if (cands.isEmpty()) {
            return null;
        }
        return cands.get(ThreadLocalRandom.current().nextInt(cands.size()));
    }

    private void tryEnter(BotRecord leader, Spot spot, Slot slot, ClanStore.Row row, long now) {
        if (leader.inWorld()) {
            place(leader, spot, slot, row, now);
            return;
        }
        final String title = title();
        final String err = world.enterAtPost(leader, new Location(spot.x(), spot.y(), spot.z()), POST_RADIUS, p -> {
            p.setTitle(title);
            p.broadcastUserInfo();
        });
        if (err == null) {
            place(leader, spot, slot, row, now);
        }
    }

    private void place(BotRecord r, Spot spot, Slot slot, ClanStore.Row row, long now) {
        final Player p = r.player();
        if (p == null) {
            return;
        }
        // already assigned elsewhere?
        if (activeBotIds.contains(r.botId()) && (slot.botId != r.botId())) {
            return;
        }
        slot.botId = r.botId();
        slot.clanId = row.clanId();
        slot.seenAt = now;
        activeBotIds.add(r.botId());
        r.setPost(true);
        ensureTitle(p);
        final double dist = Math.hypot(p.getX() - spot.x(), p.getY() - spot.y());
        if (dist > (POST_RADIUS + 50)) {
            final org.l2start.bots.api.Pos to = BotWorld.toPost(p, new org.l2start.bots.api.Pos(spot.x(), spot.y(), spot.z()), POST_RADIUS);
            ctx.log().info((kind == Kind.CLAN ? "clan" : "academy") + " recruiter " + r.name() + " to its post at the GK of " + spot.town() + ": " + to);
        }
        if (halls != null) {
            halls.ensureForRecruiter(row.clanId(), spot.x(), spot.y(), p);
        }
    }

    private void ensureTitle(Player p) {
        if (!ownTitle(p.getTitle())) {
            p.setTitle(title());
            p.broadcastUserInfo();
        }
    }

    private void maybeShout(Slot slot, Player bot, long now) {
        final long gap = shoutGapMs(slot);
        if ((now - slot.lastShoutAt) < gap) {
            return;
        }
        // Outside 6g SocialBoard world LFP limit; only per-bot cooldown applies.
        slot.lastShoutAt = now;
        slot.nextShoutGapMs = nextShoutGapMs();
        final String text = shoutText(slot);
        final ChatType type = ThreadLocalRandom.current().nextBoolean() ? ChatType.SHOUT : ChatType.TRADE;
        bot.broadcastPacket(new CreatureSay(bot, type, bot.getName(), text));
    }

    private long shoutGapMs(Slot slot) {
        if (slot.nextShoutGapMs <= 0) {
            slot.nextShoutGapMs = nextShoutGapMs();
        }
        return slot.nextShoutGapMs;
    }

    private long nextShoutGapMs() {
        final long span = Math.max(0, shoutMaxMs - shoutMinMs);
        return shoutMinMs + (span == 0 ? 0 : ThreadLocalRandom.current().nextLong(span + 1));
    }

    String shoutText(Slot slot) {
        final Clan clan = ClanTable.getInstance().getClan(slot.clanId);
        final String name = clan == null ? "?" : clan.getName();
        final int lvl = clan == null ? 0 : clan.getLevel();
        String town = ClanHallAssign.hallTownLabel(clan);
        if (kind == Kind.CLAN) {
            return clanShoutLine(name, lvl, town, clan == null ? 0 : clan.getMembersCount(), slot.spot.town());
        }
        if (town.isBlank()) {
            town = slot.spot.town();
        }
        return ClanHallAssign.shoutLine(name, lvl, town, joinReward, gradReward) + " " + standsAt(slot.spot.town());
    }

    private void tryInvite(Player live, Player bot, Slot slot, long now) {
        if ((live == null) || (bot == null) || (slot == null)) {
            return;
        }
        if (kind == Kind.CLAN) {
            if (clanEligible(live.getClan() != null, live.getLevel()) && ClanInvites.invite(bot, live, 0)) {
                ctx.log().info("clan invite " + live.getName() + " <- " + bot.getName());
            }
            return;
        }
        if (!AcademyRewards.eligible(live.getClan() != null, live.getLevel(), live.getPlayerClass().level())) {
            return;
        }
        ensureAcademy(slot.clanId);
        if (ClanInvites.invite(bot, live, Clan.SUBUNIT_ACADEMY)) {
            inviters.put(live.getObjectId(), bot.getObjectId());
            ctx.log().info("academy invite " + live.getName() + " <- " + bot.getName());
        }
    }

    private Slot findSlot(int botId) {
        for (List<Slot> slots : byTown.values()) {
            for (Slot s : slots) {
                if (s.botId == botId) {
                    return s;
                }
            }
        }
        return null;
    }

    private void ensureAcademy(int clanId) {
        final Clan clan = ClanTable.getInstance().getClan(clanId);
        if (clan == null) {
            return;
        }
        try {
            StockClanFactory.ensureAcademy(clan);
        } catch (Exception e) {
            ctx.log().warn("ensure academy " + clan.getName() + ": " + e.getMessage());
        }
    }

    private void logoutSlot(Slot slot) {
        if (slot.botId <= 0) {
            return;
        }
        activeBotIds.remove(slot.botId);
        final BotRecord r = byBot(slot.botId);
        if ((r != null) && r.inWorld()) {
            final Player p = r.player();
            if (p != null) {
                p.setTitle("");
                p.broadcastUserInfo();
                // bag loot must not persist (same rule as director logout)
                try {
                    for (org.l2jmobius.gameserver.entity.item.instance.Item it : List.copyOf(p.getInventory().getItems())) {
                        if (it.isEquipped()) {
                            continue;
                        }
                        if (it.isEtcItem() && (it.getItemType() == org.l2jmobius.gameserver.entity.item.type.EtcItemType.ARROW)) {
                            continue;
                        }
                        p.getInventory().destroyItem(org.l2jmobius.gameserver.entity.item.enums.ItemProcessType.DESTROY, it, p, null);
                    }
                } catch (Exception e) {
                    ctx.log().warn("recruiter cleanup " + r.name() + ": " + e.getMessage());
                }
            }
            world.leave(r, false);
        }
        slot.botId = 0;
        slot.clanId = 0;
    }

    private void logoutAll() {
        for (List<Slot> slots : byTown.values()) {
            for (Slot slot : slots) {
                logoutSlot(slot);
            }
        }
    }

    private BotRecord byBot(int botId) {
        for (BotRecord r : registry.all()) {
            if (r.botId() == botId) {
                return r;
            }
        }
        return null;
    }
}
