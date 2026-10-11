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
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
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
            if (s.getTargetType() == TargetType.ONE) {
                if (s.hasEffectType(EffectType.MAGICAL_ATTACK)) {
                    kind = SkillView.Kind.NUKE;
                } else if (s.hasEffectType(EffectType.PHYSICAL_ATTACK)) {
                    kind = SkillView.Kind.STRIKE;
                }
            }
            skills.add(new SkillView(s.getId(), s.getLevel(), kind, s.getMpConsume(), s.getCastRange(), !p.isSkillDisabled(s)));
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
        return new SelfView(p.getObjectId(), p.getName(), p.getPlayerClass().getId(), p.isMageClass(), level, expPct, p.getSp(), p.getCurrentHp(), p.getMaxHp(),
            p.getCurrentMp(), p.getMaxMp(), pos(p), p.isDead(), p.isSitting(), p.isMoving(), p.isInCombat(), p.isAttackingNow(), p.isCastingNow(), t == null ? 0 : t.getObjectId(),
            p.isInsideZone(ZoneId.PEACE) || p.isInsideZone(ZoneId.TOWN), p.isTeleporting(), String.valueOf(p.getAI().getIntention()), p.getActiveWeaponInstance() != null, List.copyOf(skills),
            p.getCurrentCp(), p.getMaxCp(), p.getPhysicalAttackRange(), (p.getActiveWeaponItem() != null) && (p.getActiveWeaponItem().getItemType() == WeaponType.BOW));
    }

    @Override
    public SelfView self() {
        return self;
    }

    NpcView view(Npc n) {
        boolean engaged = false;
        int targetId = 0;
        final WorldObject t = n.getTarget();
        if (t != null) {
            targetId = t.getObjectId();
            engaged = (t != p) && t.isPlayable();
        }
        if (!engaged && (n instanceof Attackable a)) {
            for (Creature c : a.getAggroList().keySet()) {
                if ((c != p) && c.isPlayable()) {
                    engaged = true;
                    break;
                }
            }
        }
        final Pos at = pos(n);
        return new NpcView(n.getObjectId(), n.getId(), n.getName(), n.getLevel(), at, at.distance(self.pos()), n.getMaxHp() <= 0 ? 0 : (n.getCurrentHp() * 100) / n.getMaxHp(), n.isDead(),
            n.isMonster(), n.isAggressive(), n.isAutoAttackable(p), targetId, engaged, n.getPhysicalAttackRange(), n.isAttackingNow(), aggroRange(n));
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
}
