package org.l2start.bots.core;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.Pos;

/**
 * {@link Actuators} on stock AI intentions and requests, each one checked by a validator first (alive, distance,
 * peace zone, geodata, target exists). Nothing here goes around a stock rule: the stock code still checks
 * everything it checks for a client.
 */
final class MobiusActuators implements Actuators {
    static final int MAX_MOVE = 4000;
    static final int MAX_TARGET = 2500;
    static final int SAY_PER_MINUTE = 6;

    /** Where actions are reported (action log, metrics) and how a bot leaves the world. */
    interface Sink {
        void done(BotRecord r, String action, Object arg, ActionResult result);

        void logout(BotRecord r);

        /** Run off the bot's shard (database writes). */
        void async(String name, Runnable task);

        long now();
    }

    private final BotRecord r;
    private final BotWorld world;
    private final Sink sink;
    private final long[] said = new long[SAY_PER_MINUTE];
    private int saidPos;
    /** Gait asked by the brain: false = run (the default, like a client). */
    private volatile boolean walk;

    MobiusActuators(BotRecord r, BotWorld world, Sink sink) {
        this.r = r;
        this.world = world;
        this.sink = sink;
    }

    private ActionResult done(String action, Object arg, ActionResult res) {
        sink.done(r, action, arg, res);
        return res;
    }

    /** The bot's player when it can act at all (in the world, alive, not teleporting). */
    private Player alive() {
        final Player p = r.player();
        if ((p == null) || p.isDead() || p.isTeleporting()) {
            return null;
        }
        gait(p);
        return p;
    }

    /** Keep the asked gait before every action (stock code or a revive may have changed it); broadcasts only on change. */
    private void gait(Player p) {
        if (walk == p.isRunning()) {
            if (walk) {
                p.setWalking();
            } else {
                p.setRunning();
            }
        }
    }

    private static Creature creature(Player p, int objectId) {
        return creature(p, objectId, false);
    }

    private static Creature creature(Player p, int objectId, boolean deadToo) {
        final WorldObject o = World.findObject(objectId);
        if (!(o instanceof Creature c) || (c.isDead() && !deadToo) || !c.isSpawned() || (c.calculateDistance2D(p) > MAX_TARGET)) {
            return null;
        }
        return c;
    }

    @Override
    public ActionResult moveTo(Pos to) {
        final Player p = alive();
        if ((p == null) || p.isSitting() || p.isMovementDisabled() || (Math.hypot(to.x() - p.getX(), to.y() - p.getY()) > MAX_MOVE)) {
            return done("moveTo", to, ActionResult.INVALID);
        }
        if (world.requireGeodata() && !GeoEngine.getInstance().hasGeo(to.x(), to.y())) {
            return done("moveTo", to, ActionResult.NOT_REACHABLE);
        }
        if ((GeoEngineConfig.PATHFINDING == 0) && !GeoEngine.getInstance().canMoveToTarget(p.getX(), p.getY(), p.getZ(), to.x(), to.y(), to.z(), p.getInstanceId())) {
            return done("moveTo", to, ActionResult.NOT_REACHABLE);
        }
        p.getAI().setIntentionMoveTo(new Location(to.x(), to.y(), to.z()));
        return done("moveTo", to, ActionResult.OK);
    }

    @Override
    public ActionResult follow(int objectId) {
        final Player p = alive();
        final Creature c = p == null ? null : creature(p, objectId);
        if ((c == null) || (c == p) || p.isSitting()) {
            return done("follow", objectId, ActionResult.INVALID);
        }
        p.getAI().setIntentionFollow(c);
        return done("follow", objectId, ActionResult.OK);
    }

    @Override
    public ActionResult setTarget(int objectId) {
        final Player p = alive();
        final Creature c = p == null ? null : creature(p, objectId);
        if (c == null) {
            return done("setTarget", objectId, ActionResult.INVALID);
        }
        p.setTarget(c);
        return done("setTarget", objectId, ActionResult.OK);
    }

    @Override
    public ActionResult attack(int objectId) {
        final Player p = alive();
        final Creature c = p == null ? null : creature(p, objectId);
        final String name = c == null ? String.valueOf(objectId) : c.getName();
        if ((c == null) || (c == p) || p.isSitting() || p.isAttackDisabled() || !c.isAutoAttackable(p) || p.isInsidePeaceZone(p, c)) {
            return done("attack", name, ActionResult.INVALID);
        }
        if (!GeoEngine.getInstance().canSeeTarget(p, c) && (GeoEngineConfig.PATHFINDING == 0)) {
            return done("attack", name, ActionResult.NOT_REACHABLE);
        }
        p.setTarget(c);
        p.getAI().setIntentionAttack(c);
        return done("attack", name, ActionResult.OK);
    }

    @Override
    public ActionResult cast(int skillId, int objectId) {
        final Player p = alive();
        final Skill skill = p == null ? null : p.getKnownSkill(skillId);
        final boolean corpse = (skill != null) && MobiusWorldView.corpse(skill);
        final Creature c = p == null ? null : creature(p, objectId, corpse);
        final String arg = skillId + "->" + (c == null ? String.valueOf(objectId) : c.getName());
        if ((skill == null) || (c == null) || p.isSitting() || p.isAllSkillsDisabled() || (corpse && (!c.isDead() || !c.isAttackable()))
            || (!corpse && ((skill.getEffectPoint() < 0) || skill.isDebuff()) && (!c.isAutoAttackable(p) || p.isInsidePeaceZone(p, c)))) {
            return done("cast", arg, ActionResult.INVALID);
        }
        if (p.isSkillDisabled(skill) || p.isCastingNow() || (p.getCurrentMp() < skill.getMpConsume())) {
            return done("cast", arg, ActionResult.COOLDOWN);
        }
        p.setTarget(c);
        return done("cast", arg, p.useMagic(skill, false, false) ? ActionResult.OK : ActionResult.INVALID);
    }

    @Override
    public ActionResult useItem(int itemId) {
        final Player p = alive();
        final Item item = p == null ? null : p.getInventory().getItemByItemId(itemId);
        if (item == null) {
            return done("useItem", itemId, ActionResult.INVALID);
        }
        if (item.isEquipable()) {
            p.useEquippableItem(item, true);
            return done("useItem", itemId, ActionResult.OK);
        }
        final IItemHandler handler = ItemHandler.getInstance().getHandler(item.getEtcItem());
        if ((handler == null) || p.isSitting()) {
            return done("useItem", itemId, ActionResult.INVALID);
        }
        return done("useItem", itemId, handler.onItemUse(p, item, false) ? ActionResult.OK : ActionResult.INVALID);
    }

    @Override
    public ActionResult pickUp(int dropObjectId) {
        final Player p = alive();
        final WorldObject o = World.findObject(dropObjectId);
        if ((p == null) || p.isSitting() || !(o instanceof Item it) || (it.getItemLocation() != ItemLocation.VOID) || !it.isSpawned() || (it.calculateDistance2D(p) > MAX_TARGET)) {
            return done("pickUp", dropObjectId, ActionResult.INVALID);
        }
        if (it.getDropProtection().isProtected() && (it.getDropProtection().getOwner() != p)) {
            return done("pickUp", dropObjectId, ActionResult.INVALID);
        }
        p.getAI().setIntentionPickUp(it);
        return done("pickUp", dropObjectId, ActionResult.OK);
    }

    @Override
    public ActionResult sit() {
        final Player p = alive();
        if ((p == null) || p.isSitting() || p.isCastingNow() || p.isAttackingNow()) {
            return done("sit", "", ActionResult.INVALID);
        }
        if (p.isMoving()) {
            p.stopMove(null);
        }
        p.sitDown(); // stock: sets REST and plays the sit animation
        return done("sit", "", p.isSitting() ? ActionResult.OK : ActionResult.INVALID);
    }

    @Override
    public ActionResult stand() {
        final Player p = alive();
        if ((p == null) || !p.isSitting()) {
            return done("stand", "", ActionResult.INVALID);
        }
        p.standUp();
        return done("stand", "", ActionResult.OK);
    }

    @Override
    public ActionResult say(String text) {
        final Player p = alive();
        final long now = sink.now();
        if ((p == null) || (text == null) || text.isBlank() || (text.length() > 100)) {
            return done("say", text, ActionResult.INVALID);
        }
        if ((said[saidPos] != 0) && ((now - said[saidPos]) < 60_000)) {
            return done("say", text, ActionResult.COOLDOWN);
        }
        said[saidPos] = now;
        saidPos = (saidPos + 1) % said.length;
        p.broadcastPacket(new CreatureSay(p, ChatType.GENERAL, p.getName(), text));
        return done("say", text, ActionResult.OK);
    }

    @Override
    public ActionResult returnToTown() {
        final Player p = r.player();
        if ((p == null) || p.isTeleporting()) {
            return done("returnToTown", "", ActionResult.INVALID);
        }
        if (p.isDead()) {
            world.returnToTown(r);
            return done("returnToTown", "dead", ActionResult.OK);
        }
        final Location loc = MapRegionData.getInstance().getTeleToLocation(p, TeleportWhereType.TOWN);
        if (loc == null) {
            return done("returnToTown", "", ActionResult.NOT_REACHABLE);
        }
        if (p.isSitting()) {
            p.standUp();
        }
        p.abortAttack();
        p.abortCast();
        p.getAI().setIntentionActive();
        p.teleToLocation(loc, true); // like a scroll of escape; bots-core finishes the teleport (no client)
        return done("returnToTown", "", ActionResult.OK);
    }

    @Override
    public int learnSkills() {
        final Player p = alive();
        final int n = p == null ? 0 : SkillLearner.preview(p);
        if (n > 0) {
            // adding a skill writes character_skills: done on the stock pool, not on the shard
            sink.async("learn skills " + r.name(), () -> {
                if (r.player() == p) {
                    SkillLearner.learn(p);
                }
            });
        }
        done("learnSkills", n, ActionResult.OK);
        return n;
    }

    @Override
    public ActionResult setWalking(boolean walk) {
        this.walk = walk;
        final Player p = alive();
        return done("setWalking", walk, p == null ? ActionResult.INVALID : ActionResult.OK);
    }

    @Override
    public ActionResult stop() {
        final Player p = alive();
        if (p == null) {
            return done("stop", "", ActionResult.INVALID);
        }
        if (!p.isSitting()) {
            p.getAI().setIntentionActive();
        }
        return done("stop", "", ActionResult.OK);
    }

    /** Its own summon when it is in the world and alive. */
    private static Summon pet(Player p) {
        final Summon s = p == null ? null : p.getSummon();
        return (s == null) || s.isDead() || !s.isSpawned() ? null : s;
    }

    @Override
    public ActionResult petAttack(int objectId) {
        final Player p = alive();
        final Summon s = pet(p);
        final Creature c = s == null ? null : creature(p, objectId);
        final String name = c == null ? String.valueOf(objectId) : c.getName();
        // the same checks as the client's pet window "Attack" (RequestActionUse 16/22) plus the bot's validator
        if ((c == null) || (c == p) || (c == s) || s.isBetrayed() || s.isMovementDisabled() || !c.isAutoAttackable(p) || p.isInsidePeaceZone(p, c)) {
            return done("petAttack", name, ActionResult.INVALID);
        }
        s.setFollowStatus(false);
        s.doSummonAttack(c);
        return done("petAttack", name, ActionResult.OK);
    }

    @Override
    public ActionResult petFollow() {
        final Player p = alive();
        final Summon s = pet(p);
        if (s == null) {
            return done("petFollow", "", ActionResult.INVALID);
        }
        s.cancelAction();
        s.setFollowStatus(true);
        return done("petFollow", "", ActionResult.OK);
    }

    @Override
    public ActionResult unsummon() {
        final Player p = r.player();
        if ((p == null) || !p.hasSummon()) {
            return done("unsummon", "", ActionResult.INVALID);
        }
        BotWorld.dismissSummon(p);
        return done("unsummon", "", ActionResult.OK);
    }

    @Override
    public ActionResult logout() {
        if (r.player() == null) {
            return done("logout", "", ActionResult.INVALID);
        }
        sink.logout(r);
        return done("logout", "", ActionResult.OK);
    }
}
