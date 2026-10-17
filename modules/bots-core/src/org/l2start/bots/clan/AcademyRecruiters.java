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
 */
public final class AcademyRecruiters {
    public static final String TITLE = "Набор в академию";
    private static final int NEAR = 6000;
    private static final long STAY_MS = 5 * 60_000L;
    private static final long SHOUT_GAP_MS = 90_000L;

    public record Spot(String town, int x, int y, int z) {
    }

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

    public AcademyRecruiters(ModuleContext ctx, ClanService clans, BotRegistry registry, BotWorld world) {
        this.ctx = ctx;
        this.clans = clans;
        this.registry = registry;
        this.world = world;
        for (Spot s : TOWNS) {
            byTown.put(s.town(), List.of(new Slot(s), new Slot(s)));
        }
    }

    public void configure(boolean on) {
        enabled = on;
    }

    public void setHalls(ClanHallAssign assign) {
        halls = assign;
    }

    public void setRewards(int join, int graduate) {
        joinReward = Math.max(0, join);
        gradReward = Math.max(0, graduate);
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
        for (Spot s : TOWNS) {
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
        for (Spot spot : TOWNS) {
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
            }
        }
    }

    /** Whisper to a recruiter asking for academy. */
    public boolean onWhisper(Player from, BotRecord to, String text) {
        if (!enabled || (from == null) || (to == null) || !isRecruiter(to.botId())) {
            return false;
        }
        final WhisperParse.Intent intent = WhisperParse.parse(text);
        if ((intent != WhisperParse.Intent.ASK_CLAN) && !asksAcademy(text)) {
            return false;
        }
        final Player bot = to.player();
        if (bot == null) {
            return false;
        }
        tryInvite(from, bot, findSlot(to.botId()), System.currentTimeMillis());
        return true;
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
            if (used.contains(r.clanId()) || (r.level() < 5)) {
                continue;
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
        final int dx = ThreadLocalRandom.current().nextInt(-120, 121);
        final int dy = ThreadLocalRandom.current().nextInt(-120, 121);
        final String err = world.enterPrepared(leader, new Location(spot.x() + dx, spot.y() + dy, spot.z()), p -> {
            p.setTitle(TITLE);
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
        ensureTitle(p);
        final double dist = Math.hypot(p.getX() - spot.x(), p.getY() - spot.y());
        if (dist > 400) {
            p.teleToLocation(spot.x() + ThreadLocalRandom.current().nextInt(-80, 81),
                spot.y() + ThreadLocalRandom.current().nextInt(-80, 81), spot.z(), false);
        }
    }

    private static void ensureTitle(Player p) {
        if (!TITLE.equals(p.getTitle())) {
            p.setTitle(TITLE);
            p.broadcastUserInfo();
        }
    }

    private void maybeShout(Slot slot, Player bot, long now) {
        if ((now - slot.lastShoutAt) < SHOUT_GAP_MS) {
            return;
        }
        if (!social.tryShout(now)) {
            return;
        }
        slot.lastShoutAt = now;
        final String text = shoutText(slot);
        final ChatType type = ThreadLocalRandom.current().nextBoolean() ? ChatType.SHOUT : ChatType.TRADE;
        bot.broadcastPacket(new CreatureSay(bot, type, bot.getName(), text));
    }

    String shoutText(Slot slot) {
        final Clan clan = ClanTable.getInstance().getClan(slot.clanId);
        final String name = clan == null ? "?" : clan.getName();
        final int lvl = clan == null ? 0 : clan.getLevel();
        String town = ClanHallAssign.hallTownLabel(clan);
        if (town.isBlank()) {
            town = slot.spot.town();
        }
        return ClanHallAssign.shoutLine(name, lvl, town, joinReward, gradReward);
    }

    private void tryInvite(Player live, Player bot, Slot slot, long now) {
        if ((live == null) || (bot == null) || (slot == null)) {
            return;
        }
        if (!AcademyRewards.eligible(live.getClan() != null, live.getLevel(), live.getPlayerClass().level())) {
            return;
        }
        ensureAcademy(slot.clanId);
        if (ClanInvites.invite(bot, live, Clan.SUBUNIT_ACADEMY)) {
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
