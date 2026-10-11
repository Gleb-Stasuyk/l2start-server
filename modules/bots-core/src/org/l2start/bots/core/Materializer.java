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
    private static final int[] SOULSHOTS = { 1835, 1463, 1464, 1465, 1466, 1467 };
    private static final int[] SPIRITSHOTS = { 3947, 3948, 3949, 3950, 3951, 3952 };

    private final GearTable gear;

    Materializer(GearTable gear) {
        this.gear = gear;
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
        final GearTable.Plan plan = gear.plan(future, target, seed);
        if (!plan.items().isEmpty()) {
            final int n = equip(p, plan);
            sb.append(", ").append(plan.grade()).append(" gear ").append(n).append(" new");
        }
        cleanup(p);
        sb.append(", ").append(shots(p, plan.items().isEmpty() ? 0 : plan.grade().ordinal()));
        sb.append(", ").append(potions(p, target));
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
        return sb.toString();
    }

    private int equip(Player p, GearTable.Plan plan) {
        final Set<Integer> want = new HashSet<>(plan.items());
        // old weapons and armour that are not in the plan go away (jewels stay)
        for (Item it : new ArrayList<>(p.getInventory().getItems())) {
            if ((it.isWeapon() || it.isArmor()) && !want.contains(it.getId()) && !jewel(it)) {
                if (it.isEquipped()) {
                    p.getInventory().unEquipItemInSlot(it.getLocationSlot());
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
            }
            if ((it != null) && !it.isEquipped()) {
                p.getInventory().equipItem(it);
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
    private static String shots(Player p, int grade) {
        final int g = Math.max(0, Math.min(SOULSHOTS.length - 1, grade));
        final int id = p.isMageClass() ? SPIRITSHOTS[g] : SOULSHOTS[g];
        p.getInventory().addItem(ItemProcessType.REWARD, id, p.isMageClass() ? 800 : 2000, p, null);
        for (Integer old : new ArrayList<>(p.getAutoSoulShot())) {
            p.removeAutoSoulShot(old);
        }
        p.addAutoSoulShot(id);
        return "shots " + id;
    }

    /** HP potions of the level (phase 4a), like a player farming; they vanish with the rest on logout. */
    static int potionFor(int level) {
        return level < 20 ? 1060 : level < 40 ? 1061 : 1539; // Lesser / Healing / Greater Healing Potion
    }

    static final int POTIONS = 20;

    private static String potions(Player p, int level) {
        final int id = potionFor(level);
        p.getInventory().addItem(ItemProcessType.REWARD, id, POTIONS, p, null);
        return POTIONS + " potions " + id;
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
        final List<Item> junk = new ArrayList<>();
        for (Item it : p.getInventory().getItems()) {
            if (it.isEquipped() || (it.isEtcItem() && (it.getItemType() == EtcItemType.ARROW))) {
                continue;
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
}
