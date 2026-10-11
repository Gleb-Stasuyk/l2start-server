package org.l2start.bots.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2start.bots.api.BotEvent;
import org.l2start.bots.api.DropView;
import org.l2start.bots.api.Geo;
import org.l2start.bots.api.NpcView;
import org.l2start.bots.api.PlayerView;
import org.l2start.bots.api.Pos;
import org.l2start.bots.api.SelfView;
import org.l2start.bots.api.SkillView;
import org.l2start.bots.api.WorldView;

/** The {@link WorldView} of one tick, read from the stock objects. Lists are built on first use. */
final class MobiusWorldView implements WorldView {
    static final int MAX_OBJECTS = 64;

    private final Player p;
    private final BotRegistry registry;
    private final int radius;
    private final List<BotEvent> events;
    private final SelfView self;
    private List<NpcView> npcs;
    private List<PlayerView> players;
    private List<DropView> drops;

    MobiusWorldView(Player p, BotRegistry registry, int radius, List<BotEvent> events) {
        this.p = p;
        this.registry = registry;
        this.radius = radius;
        this.events = events;
        self = buildSelf();
    }

    static Pos pos(WorldObject o) {
        return new Pos(o.getX(), o.getY(), o.getZ());
    }

    private SelfView buildSelf() {
        final List<SkillView> skills = new ArrayList<>();
        for (Skill s : p.getSkills().values()) {
            if (!s.isActive()) {
                continue;
            }
            SkillView.Kind kind = SkillView.Kind.OTHER;
            int left = 0;
            if (s.getTargetType() == TargetType.ONE) {
                if (s.hasEffectType(EffectType.MAGICAL_ATTACK)) {
                    kind = SkillView.Kind.NUKE;
                } else if (s.hasEffectType(EffectType.PHYSICAL_ATTACK)) {
                    kind = SkillView.Kind.STRIKE;
                }
            }
            if ((kind == SkillView.Kind.OTHER) && corpse(s)) {
                kind = SkillView.Kind.CORPSE; // Sweeper; also the necromancer's summons from a body
            } else if ((kind == SkillView.Kind.OTHER) && (s.hasEffectType(EffectType.SUMMON) || s.hasEffectType(EffectType.SUMMON_PET))) {
                kind = SkillView.Kind.SUMMON;
            }
            if ((kind == SkillView.Kind.OTHER) && !s.isDebuff()) {
                if (s.isToggle()) {
                    kind = SkillView.Kind.TOGGLE;
                    left = p.getEffectList().isAffectedBySkill(s.getId()) ? -1 : 0;
                } else if (selfBuff(s)) {
                    kind = SkillView.Kind.BUFF;
                    final BuffInfo on = s.getAbnormalType() == AbnormalType.NONE ? p.getEffectList().getBuffInfoBySkillId(s.getId()) : p.getEffectList().getBuffInfoByAbnormalType(s.getAbnormalType());
                    left = on == null ? 0 : Math.max(1, on.getTime());
                }
            }
            skills.add(new SkillView(s.getId(), s.getLevel(), kind, s.getMpConsume(), s.getCastRange(), !p.isSkillDisabled(s), left, Math.max(0, s.getItemConsumeId()),
                Math.max(0, s.getItemConsumeCount())));
        }
        skills.sort(Comparator.comparingInt(SkillView::id));
        final int level = p.getLevel();
        double expPct = 0;
        try {
            final long from = ExperienceData.getInstance().getExpForLevel(level);
            final long to = ExperienceData.getInstance().getExpForLevel(level + 1);
            expPct = to > from ? ((p.getExp() - from) * 100.0) / (to - from) : 0;
        } catch (RuntimeException ignored) {
            // max level
        }
        final WorldObject t = p.getTarget();
        final Summon pet = p.getSummon();
        final boolean petUp = (pet != null) && pet.isSpawned() && !pet.isDead();
        final WorldObject petTarget = petUp ? pet.getTarget() : null;
        return new SelfView(p.getObjectId(), p.getName(), p.getPlayerClass().getId(), p.isMageClass(), level, expPct, p.getSp(), p.getCurrentHp(), p.getMaxHp(),
            p.getCurrentMp(), p.getMaxMp(), pos(p), p.isDead(), p.isSitting(), p.isMoving(), p.isInCombat(), p.isAttackingNow(), p.isCastingNow(), t == null ? 0 : t.getObjectId(),
            p.isInsideZone(ZoneId.PEACE) || p.isInsideZone(ZoneId.TOWN), p.isTeleporting(), String.valueOf(p.getAI().getIntention()), p.getActiveWeaponInstance() != null, List.copyOf(skills),
            p.getCurrentCp(), p.getMaxCp(), p.getPhysicalAttackRange(), (p.getActiveWeaponItem() != null) && (p.getActiveWeaponItem().getItemType() == WeaponType.BOW),
            p.getCharges(), petUp ? pet.getObjectId() : 0, petUp && (pet.getMaxHp() > 0) ? (pet.getCurrentHp() * 100) / pet.getMaxHp() : 0, petTarget == null ? 0 : petTarget.getObjectId());
    }

    @Override
    public SelfView self() {
        return self;
    }

    NpcView view(Npc n) {
        boolean engaged = false;
        int targetId = 0;
        final WorldObject t = n.getTarget();
        final Summon own = p.getSummon(); // the bot's own summon fights for the bot: not "someone else"
        if (t != null) {
            targetId = t.getObjectId();
            engaged = (t != p) && (t != own) && t.isPlayable();
        }
        if (!engaged && (n instanceof Attackable a)) {
            for (Creature c : a.getAggroList().keySet()) {
                if ((c != p) && (c != own) && c.isPlayable()) {
                    engaged = true;
                    break;
                }
            }
        }
        final Pos at = pos(n);
        return new NpcView(n.getObjectId(), n.getId(), n.getName(), n.getLevel(), at, at.distance(self.pos()), n.getMaxHp() <= 0 ? 0 : (n.getCurrentHp() * 100) / n.getMaxHp(), n.isDead(),
            n.isMonster(), n.isAggressive(), n.isAutoAttackable(p), targetId, engaged, n.getPhysicalAttackRange(), n.isAttackingNow(), aggroRange(n),
            n.getHeading(), n.isUndead(), (n instanceof Attackable a) && a.isSpoiled());
    }

    @Override
    public List<NpcView> npcs() {
        if (npcs == null) {
            final List<NpcView> l = new ArrayList<>();
            for (Npc n : World.getVisibleObjectsInRange(p, Npc.class, radius)) {
                l.add(view(n));
            }
            l.sort(Comparator.comparingDouble(NpcView::distance));
            npcs = l.size() > MAX_OBJECTS ? List.copyOf(l.subList(0, MAX_OBJECTS)) : l;
        }
        return npcs;
    }

    @Override
    public List<PlayerView> players() {
        if (players == null) {
            final List<PlayerView> l = new ArrayList<>();
            for (Player o : World.getVisibleObjectsInRange(p, Player.class, radius)) {
                final Pos at = pos(o);
                final WorldObject t = o.getTarget();
                l.add(new PlayerView(o.getObjectId(), o.getName(), o.getLevel(), o.getPlayerClass().getId(), at, at.distance(self.pos()), o.getKarma(), o.getPvpFlag() != 0, o.isInCombat(),
                    t == null ? 0 : t.getObjectId(), registry.isBot(o)));
            }
            l.sort(Comparator.comparingDouble(PlayerView::distance));
            players = l.size() > MAX_OBJECTS ? List.copyOf(l.subList(0, MAX_OBJECTS)) : l;
        }
        return players;
    }

    @Override
    public List<DropView> drops() {
        if (drops == null) {
            final List<DropView> l = new ArrayList<>();
            for (Item it : World.getVisibleObjectsInRange(p, Item.class, radius)) {
                final Pos at = pos(it);
                final boolean mine = !it.getDropProtection().isProtected() || (it.getDropProtection().getOwner() == p);
                l.add(new DropView(it.getObjectId(), it.getId(), it.getCount(), at, at.distance(self.pos()), mine));
            }
            l.sort(Comparator.comparingDouble(DropView::distance));
            drops = l.size() > MAX_OBJECTS ? List.copyOf(l.subList(0, MAX_OBJECTS)) : l;
        }
        return drops;
    }

    @Override
    public List<BotEvent> events() {
        return events;
    }

    @Override
    public Geo geo() {
        return MobiusGeo.INSTANCE;
    }

    @Override
    public long itemCount(int itemId) {
        return p.getInventory().getInventoryItemCount(itemId, -1, false);
    }

    @Override
    public NpcView npc(int objectId) {
        return World.findObject(objectId) instanceof Npc n ? view(n) : null;
    }

    /** Aggro range of an aggressive monster (NPC data; the usual 300 when the data has none), else 0. */
    static int aggroRange(Npc n) {
        if (!n.isAggressive()) {
            return 0;
        }
        final int r = n.getTemplate().getAggroRange();
        return r > 0 ? r : NpcView.AGGRO;
    }

    /** Used on a dead monster's body (Sweeper). */
    static boolean corpse(Skill s) {
        return switch (s.getTargetType()) {
            case CORPSE_MOB -> true;
            default -> false;
        };
    }

    /**
     * A lasting good effect a player can put on himself (phase 4b): continuous, 2 minutes or longer (no short panic
     * skills like Ultimate Defense or Frenzy), cast on himself, the party or a friendly target, no items consumed, no
     * dances or songs (they need a party around).
     */
    static boolean selfBuff(Skill s) {
        if (!s.isContinuous() || s.isToggle() || s.isDebuff() || s.isDance() || s.isHealingPotionSkill() || (s.getAbnormalTime() < 120) || (s.getItemConsumeCount() > 0)
            || (s.getAbnormalType() == null)) {
            return false;
        }
        return switch (s.getTargetType()) {
            case SELF, ONE, PARTY, CLAN, PARTY_CLAN, AURA_FRIENDLY -> s.getEffectPoint() >= 0;
            default -> false;
        };
    }
}
