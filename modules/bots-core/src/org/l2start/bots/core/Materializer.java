package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.EtcItemType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.director.GearTable;
import org.l2start.bots.persona.Persona;

/**
 * Makes a bot fit the place it is going to (plan 20.4), BEFORE it becomes visible: level and experience, profession,
 * the skills of its class up to the level (for free: it learned them while nobody watched), equipment by grade and
 * profession. And keeps the economy clean: loot, adena and old gear vanish on the way in and out.
 * Runs on a stock pool thread (database writes), never on a shard.
 */
final class Materializer {
    /** Arrows by grade (NONE, D, C, B, A, S): a bow needs them. */
    private static final int[] ARROWS = { 17, 1341, 1342, 1343, 1344, 1345 };

    /** Soulshots and blessed spiritshots by grade (NONE, D, C, B, A, S). */
    static final int[] SOULSHOTS = { 1835, 1463, 1464, 1465, 1466, 1467 };
    static final int[] SPIRITSHOTS = { 3947, 3948, 3949, 3950, 3951, 3952 };

    /** Phase 6f: item ids a party mate may gift through the trade window (any shot grade + HP potions). */
    static java.util.Set<Integer> partyGiftIds() {
        final java.util.HashSet<Integer> s = new java.util.HashSet<>();
        for (int id : SOULSHOTS) {
            s.add(id);
        }
        for (int id : SPIRITSHOTS) {
            s.add(id);
        }
        s.add(1060);
        s.add(1061);
        s.add(1539);
        return java.util.Set.copyOf(s);
    }

    private final GearTable gear;

    /** Config GradeLag of bots-core: a new grade comes min..max levels late (by greed). */
    private volatile int lagMin = 0;
    private volatile int lagMax = 5;

    /** Phase 5d: the market's wallets pay for shots and potions (null = given, like phase 4; traders never buy). */
    private volatile java.util.function.Supplier<org.l2start.bots.trade.Supplies> supplies;
    /** Phase 7c: PK gear ledger (null = no tracking; equip still works). */
    private volatile PkLedger pkLedger;
    /** Phase 7c: starting karma for a PK persona that entered washed (stock chaotic look). */
    static final int PK_ENTRY_KARMA = 12_000;

    Materializer(GearTable gear) {
        this.gear = gear;
    }

    void setSupplies(java.util.function.Supplier<org.l2start.bots.trade.Supplies> supplies) {
        this.supplies = supplies;
    }

    void setPkLedger(PkLedger ledger) {
        pkLedger = ledger;
    }

    /** How many of {@code want} the bot gets: what its wallet pays for, or all without a market. */
    private int afford(BotRecord r, int level, int itemId, int want) {
        final java.util.function.Supplier<org.l2start.bots.trade.Supplies> s = supplies;
        final org.l2start.bots.trade.Supplies market = (s == null) ? null : s.get();
        if (market == null) {
            return want;
        }
        try {
            return Math.max(0, Math.min(want, market.buy(r.botId(), level, itemId, want)));
        } catch (RuntimeException e) {
            return want; // the market's trouble must not leave a hunter without potions
        }
    }

    void setGradeLag(int min, int max) {
        lagMin = Math.max(0, Math.min(10, min));
        lagMax = Math.max(lagMin, Math.min(10, max));
    }

    /** @return what was done, for the log ("lv 41, class 2, 6 items") */
    String prepare(Player p, BotRecord r, int level) {
        final Persona persona = r.persona();
        final long seed = persona == null ? r.charId() : persona.seed();
        final int start = persona == null ? r.classId() : persona.classId();
        final int future = persona == null ? r.classId() : persona.futureClassId();
        final StringBuilder sb = new StringBuilder();
        // level: never down (real experience counts)
        final int target = Math.max(p.getLevel(), Math.min(level, ExperienceData.getInstance().getMaxLevel() - 1));
        if (target > p.getLevel()) {
            p.getStat().setLevel((byte) target);
            p.getStat().setExp(ExperienceData.getInstance().getExpForLevel(target));
        }
        sb.append("lv ").append(target);
        // profession (forward only), before it is visible: nobody sees the class change effect
        final int wanted = ClassPath.at(start, future, target);
        if ((wanted != p.getPlayerClass().getId()) && ClassPath.forward(p.getPlayerClass().getId(), wanted)) {
            p.setBaseClass(wanted);
            p.setPlayerClass(wanted);
            sb.append(", class ").append(wanted);
        }
        // skills of the class up to the level (one batch of database writes)
        final int skills = p.giveAvailableSkills(false, true, true);
        if (skills > 0) {
            sb.append(", ").append(skills).append(" skills");
        }
        // equipment
        final boolean pk = (persona != null) && persona.pk();
        final PkLedger led = pk ? pkLedger : null;
        // before equip: empty ledger = first PK materialize (entry karma); washed PK keeps ledger rows → no cheat re-red
        final boolean firstPkGear = pk && ((led == null) || led.load(r.botId()).isEmpty());
        final int lag = GearTable.lag(persona == null ? 0.5 : persona.traits().greed(), lagMin, lagMax);
        final GearTable.Plan plan = gear.plan(future, target, seed, lag);
        if (!plan.items().isEmpty()) {
            final int n = equip(p, plan, pk ? r.botId() : 0);
            sb.append(", ").append(plan.grade()).append(" gear ").append(n).append(" new");
            if (plan.weaponEnchant() > 0) {
                sb.append(", weapon +").append(plan.weaponEnchant());
            }
        }
        if (pk) {
            if (led != null) {
                led.sync(r.botId(), p); // drop forgotten object ids; register gear still in bag
            }
            if (firstPkGear && (p.getKarma() <= 0)) {
                p.setKarma(PK_ENTRY_KARMA); // first chaotic look only; wash is stock (mob kills), re-red by attacks
                sb.append(", karma ").append(PK_ENTRY_KARMA);
            } else {
                sb.append(", karma ").append(p.getKarma());
            }
        }
        cleanup(p, pk);
        sb.append(", ").append(potions(p, r, target)); // a thin wallet pays the potions first (to live), then shots
        sb.append(", ").append(shots(p, r, target, plan.items().isEmpty() ? 0 : plan.grade().ordinal()));
        p.getInventory().addItem(ItemProcessType.REWARD, SOE, 2, p, null); // like everybody: to end the hunt (phase 4d)
        final String crystals = summonItems(p);
        if (crystals != null) {
            sb.append(", ").append(crystals);
        }
        if (p.isDead()) { // logged out dead (left unseen before "To village"): never arrive as a corpse
            p.setDead(false);
            p.setIsPendingRevive(false);
        }
        p.setCurrentHpMp(p.getMaxHp(), p.getMaxMp());
        p.setCurrentCp(p.getMaxCp());
        // stock onPlayerEnter puts HP/MP/CP back to the values loaded from the database 300 ms after entering
        // (of the old, lower level): without this a bot raised to the zone's level arrives at 5-10 % HP and MP
        p.setOriginalCpHpMp(p.getMaxCp(), p.getMaxHp(), p.getMaxMp());
        persist(p);
        return sb.toString();
    }

    /**
     * Class, level and the skills given above go to the database together. Skills are written at once, the class and
     * level only on a store: a server stop before the bot's logout left the old (base) class with the new class's
     * skills, and the stock skill check on the next load reported "has invalid skill" to GMs and removed them.
     */
    static void persist(Player p) {
        try {
            p.store(false);
        } catch (RuntimeException e) {
            // the next regular store writes it
        }
    }

    /**
     * @param pkBotId &gt; 0 = PK persona: newly added gear is recorded in the ledger (real drop on death)
     */
    private int equip(Player p, GearTable.Plan plan, int pkBotId) {
        final Set<Integer> want = new HashSet<>(plan.items());
        final PkLedger led = (pkBotId > 0) ? pkLedger : null;
        // old weapons and armour that are not in the plan go away (jewels stay); PK: forget ledger rows
        for (Item it : new ArrayList<>(p.getInventory().getItems())) {
            if ((it.isWeapon() || it.isArmor()) && !want.contains(it.getId()) && !jewel(it)) {
                if (it.isEquipped()) {
                    p.getInventory().unEquipItemInSlot(it.getLocationSlot());
                }
                if (led != null) {
                    led.forget(pkBotId, it.getObjectId());
                }
                p.getInventory().destroyItem(ItemProcessType.DESTROY, it, p, null);
            }
        }
        int added = 0;
        for (int id : plan.items()) {
            Item it = p.getInventory().getItemByItemId(id);
            if (it == null) {
                it = p.getInventory().addItem(ItemProcessType.REWARD, id, 1, p, null);
                added++;
                if ((led != null) && (it != null)) {
                    led.issued(pkBotId, it.getObjectId(), id);
                }
            }
            if (it != null) {
                // visible enchant of the persona in this grade (stable: the same bot tomorrow shows the same +N)
                final int enchant = id == plan.weaponId() ? plan.weaponEnchant() : plan.armorEnchant();
                if (it.getEnchantLevel() != enchant) {
                    it.setEnchantLevel(enchant);
                }
                if (!it.isEquipped()) {
                    p.getInventory().equipItem(it);
                }
            }
        }
        if ("BOW".equals(plan.weaponKind())) {
            final int arrow = ARROWS[plan.grade().ordinal()];
            final Item have = p.getInventory().getItemByItemId(arrow);
            final long count = have == null ? 0 : have.getCount();
            if (count < 1000) {
                p.getInventory().addItem(ItemProcessType.REWARD, arrow, (int) (2000 - count), p, null);
            }
        }
        return added;
    }

    /** Shots of the weapon's grade on auto-use, like every player farming (they vanish with the rest on logout). */
    private String shots(Player p, BotRecord r, int level, int grade) {
        final int g = Math.max(0, Math.min(SOULSHOTS.length - 1, grade));
        final int id = p.isMageClass() ? SPIRITSHOTS[g] : SOULSHOTS[g];
        for (Integer old : new ArrayList<>(p.getAutoSoulShot())) {
            p.removeAutoSoulShot(old);
        }
        final int want = p.isMageClass() ? 800 : 2000;
        final Item have = p.getInventory().getItemByItemId(id);
        final int need = Math.max(0, want - ((have == null) ? 0 : (int) Math.min(want, have.getCount())));
        final int got = (need == 0) ? 0 : afford(r, level, id, need);
        if (got > 0) {
            p.getInventory().addItem(ItemProcessType.REWARD, id, got, p, null);
        }
        if ((got + ((have == null) ? 0 : have.getCount())) <= 0) {
            return "no shots (no money)";
        }
        p.addAutoSoulShot(id);
        return "shots " + id + ((got < need) ? " " + got + "/" + need + " (wallet)" : "");
    }

    /** HP potions of the level (phase 4a), like a player farming; they vanish with the rest on logout. */
    static int potionFor(int level) {
        return level < 20 ? 1060 : level < 40 ? 1061 : 1539; // Lesser / Healing / Greater Healing Potion
    }

    static final int POTIONS = 20;

    /** Scroll of Escape: 2 for every bot (they vanish with the rest on logout). */
    static final int SOE = 736;

    private String potions(Player p, BotRecord r, int level) {
        final int id = potionFor(level);
        final Item have = p.getInventory().getItemByItemId(id);
        final int need = Math.max(0, POTIONS - ((have == null) ? 0 : (int) Math.min(POTIONS, have.getCount())));
        final int got = (need == 0) ? 0 : afford(r, level, id, need);
        if (got > 0) {
            p.getInventory().addItem(ItemProcessType.REWARD, id, got, p, null);
        }
        return got + " potions " + id + ((got < need) ? " (wallet: " + got + "/" + need + ")" : "");
    }

    /** Calls of each servitor summon a bot carries the crystals for (phase 4c); they vanish with the rest on logout. */
    static final int SUMMON_CASTS = 20;
    /** Crystals on top for the servitor's upkeep (a few every 4 minutes while it is out). */
    static final int SUMMON_UPKEEP = 100;

    /** Crystals for the servitor summons it knows (Summon Kat the Cat: D-grade crystals), like a summoner farming. */
    private static String summonItems(Player p) {
        final java.util.Map<Integer, Integer> need = new java.util.TreeMap<>();
        for (Skill s : p.getSkills().values()) {
            if (SkillView.servitorSummon(s.getId()) && (s.getItemConsumeId() > 0)) {
                need.merge(s.getItemConsumeId(), (Math.max(1, s.getItemConsumeCount()) * SUMMON_CASTS) + SUMMON_UPKEEP, Math::max);
            }
        }
        if (need.isEmpty()) {
            return null;
        }
        for (java.util.Map.Entry<Integer, Integer> e : need.entrySet()) {
            p.getInventory().addItem(ItemProcessType.REWARD, e.getKey(), e.getValue(), p, null);
        }
        return "summon crystals " + need;
    }

    private static boolean jewel(Item it) {
        final String bp = String.valueOf(it.getTemplate().getBodyPart());
        return bp.contains("EAR") || bp.contains("FINGER") || bp.contains("NECK");
    }

    /** Everything that is not worn (and not arrows) vanishes: loot and adena never reach the economy. */
    int cleanup(Player p) {
        return cleanup(p, false);
    }

    /**
     * @param pk true = keep unequipped weapons/armour (ledger gear may sit in the bag until stock death drop)
     */
    int cleanup(Player p, boolean pk) {
        final List<Item> junk = new ArrayList<>();
        for (Item it : p.getInventory().getItems()) {
            if (it.isEquipped() || (it.isEtcItem() && (it.getItemType() == EtcItemType.ARROW))) {
                continue;
            }
            if (pk && (it.isWeapon() || it.isArmor())) {
                continue; // phase 7c: real PK gear stays until stock karma drop
            }
            junk.add(it);
        }
        for (Item it : junk) {
            p.getInventory().destroyItem(ItemProcessType.DESTROY, it, p, null);
        }
        return junk.size();
    }

    /** Phase 4b: buffs some bots arrive with (in the world already: the effects show at once). */
    static String arrivalBuffs(Player p, double[] shares, java.util.Random r) {
        final java.util.List<ArrivalBuffs.Buff> buffs = ArrivalBuffs.pick(p.getLevel(), p.isMageClass(), shares[0], shares[1], r);
        int n = 0;
        for (ArrivalBuffs.Buff b : buffs) {
            final org.l2jmobius.gameserver.mechanics.skill.Skill s = org.l2jmobius.gameserver.data.xml.SkillData.getInstance().getSkill(b.skillId(), b.level());
            if ((s != null) && (s.getAbnormalTime() > 0)) {
                s.applyEffects(p, p, false, Math.max(30, (int) (s.getAbnormalTime() * b.left())));
                n++;
            }
        }
        return n == 0 ? "no buffs" : (n + " buffs");
    }

    /** Highest item grade (0 none, 1 D, ... 5 S) a bot of this level may wear. */
    static int maxGrade(int level) {
        return org.l2start.bots.director.GearTable.Grade.of(Math.max(1, level)).ordinal();
    }

    /** Highest enchant a bot of this level may show: none below 20. */
    static int maxEnchant(int level) {
        return level < 20 ? 0 : Integer.MAX_VALUE;
    }

    /**
     * Every entry (any path): gear above the bot's grade goes away, low-level bots lose visible enchants. Corrects
     * personas that kept gear from an earlier, higher paper level (live: a level 8 with a D-grade +4 hammer).
     * @return how many items were removed or reset
     */
    static int fixGear(Player p) {
        final int lvl = p.getLevel();
        final int grade = maxGrade(lvl);
        final int ench = maxEnchant(lvl);
        int n = 0;
        for (Item it : new ArrayList<>(p.getInventory().getItems())) {
            if (!it.getTemplate().isEquipable()) {
                continue;
            }
            if (it.getTemplate().getCrystalType().ordinal() > grade) {
                if (it.isEquipped()) {
                    p.getInventory().unEquipItemInSlot(it.getLocationSlot());
                }
                p.getInventory().destroyItem(ItemProcessType.DESTROY, it, p, null);
                n++;
            } else if (it.getEnchantLevel() > ench) {
                it.setEnchantLevel(ench);
                n++;
            }
        }
        return n;
    }
}
