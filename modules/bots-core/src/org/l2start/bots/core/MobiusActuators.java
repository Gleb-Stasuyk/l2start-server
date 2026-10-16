package org.l2start.bots.core;

import org.l2jmobius.gameserver.config.GeoEngineConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.TeleporterData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportType;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.entity.actor.instance.Teleporter;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.teleporter.TeleportHolder;
import org.l2jmobius.gameserver.entity.teleporter.TeleportLocation;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.Pos;
import org.l2start.bots.director.GkGraph;

/**
 * {@link Actuators} on stock AI intentions and requests, each one checked by a validator first (alive, distance,
 * peace zone, geodata, target exists). Nothing here goes around a stock rule: the stock code still checks
 * everything it checks for a client.
 */
final class MobiusActuators implements Actuators {
    static final int MAX_MOVE = 4000;
    static final int MAX_TARGET = 2500;
    static final int SAY_PER_MINUTE = 6;

    /** Interaction range with a gatekeeper NPC (stock dialog distance). */
    static final int GK_RANGE = 150;

    /** Where actions are reported (action log, metrics) and how a bot leaves the world. */
    interface Sink {
        void done(BotRecord r, String action, Object arg, ActionResult result);

        void logout(BotRecord r);

        /** Phase 7e: leave bot-clan paper before stock-joining a live player's clan. */
        default void prepareJoinPlayerClan(BotRecord r, Player p) {
        }

        /** Run off the bot's shard (database writes). */
        void async(String name, Runnable task);

        long now();

        /** Gatekeeper graph for catch-up (null without the presence director). */
        default GkGraph gkGraph() {
            return null;
        }
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
        final boolean res = (skill != null) && skill.hasEffectType(org.l2jmobius.gameserver.mechanics.effects.EffectType.RESURRECTION); // on a dead party member (4g)
        final Creature c = p == null ? null : creature(p, objectId, corpse || res);
        final String arg = skillId + "->" + (c == null ? String.valueOf(objectId) : c.getName());
        if ((skill == null) || (c == null) || p.isSitting() || p.isAllSkillsDisabled() || (corpse && (!c.isDead() || !c.isAttackable())) || (res && (!c.isDead() || !c.isPlayer()))
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
        if (!speak(now)) {
            return done("say", text, ActionResult.COOLDOWN);
        }
        p.broadcastPacket(new CreatureSay(p, ChatType.GENERAL, p.getName(), text));
        return done("say", text, ActionResult.OK);
    }

    /** One more line of chat (any channel): at most {@link #SAY_PER_MINUTE} a minute. */
    private boolean speak(long now) {
        if ((said[saidPos] != 0) && ((now - said[saidPos]) < 60_000)) {
            return false;
        }
        said[saidPos] = now;
        saidPos = (saidPos + 1) % said.length;
        return true;
    }

    private static boolean okText(String text) {
        return (text != null) && !text.isBlank() && (text.length() <= 100);
    }

    // ------------------------------------------------------------------ phase 6: party and chat

    @Override
    public ActionResult answerParty(boolean accept) {
        final Player p = r.player();
        final Player requestor = p == null ? null : p.getActiveRequester();
        if ((p == null) || (requestor == null) || (p.isInParty() && accept)) {
            return done("answerParty", accept, ActionResult.INVALID);
        }
        // stock RequestAnswerJoinParty with the answer of the dialog (1 = yes, 0 = no), party rooms aside
        requestor.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.JoinParty(accept ? 1 : 0));
        ActionResult res = ActionResult.OK;
        if (accept) {
            if (requestor.isInParty()) {
                if (requestor.getParty().getMemberCount() >= 9) {
                    final org.l2jmobius.gameserver.network.serverpackets.SystemMessage sm = new org.l2jmobius.gameserver.network.serverpackets.SystemMessage(
                        org.l2jmobius.gameserver.network.SystemMessageId.THE_PARTY_IS_FULL);
                    requestor.sendPacket(sm);
                    res = ActionResult.INVALID;
                } else {
                    p.joinParty(requestor.getParty());
                }
            } else if (!p.isDead()) {
                requestor.setParty(new org.l2jmobius.gameserver.entity.groups.Party(requestor, requestor.getPartyDistributionType()));
                p.joinParty(requestor.getParty());
            }
        }
        if (requestor.isInParty()) {
            requestor.getParty().setPendingInvitation(false);
        }
        p.setActiveRequester(null);
        requestor.onTransactionResponse();
        return done("answerParty", accept, res);
    }

    @Override
    public ActionResult answerDuel(boolean accept) {
        final Player p = r.player();
        final Player requestor = p == null ? null : p.getActiveRequester();
        if ((p == null) || (requestor == null)) {
            return done("answerDuel", accept, ActionResult.INVALID);
        }
        // stock RequestDuelAnswerStart (1v1 / party from the pending ask)
        if (accept) {
            if (requestor.isInDuel() || p.isInDuel()) {
                p.setActiveRequester(null);
                requestor.onTransactionResponse();
                return done("answerDuel", accept, ActionResult.INVALID);
            }
            final boolean party = requestor.isInParty() && p.isInParty() && (requestor.getParty() != p.getParty());
            org.l2jmobius.gameserver.managers.DuelManager.getInstance().addDuel(requestor, p, party ? 1 : 0);
        } else {
            final org.l2jmobius.gameserver.network.serverpackets.SystemMessage msg = new org.l2jmobius.gameserver.network.serverpackets.SystemMessage(
                org.l2jmobius.gameserver.network.SystemMessageId.S1_HAS_DECLINED_YOUR_CHALLENGE_TO_A_DUEL);
            msg.addPcName(p);
            requestor.sendPacket(msg);
        }
        p.setActiveRequester(null);
        requestor.onTransactionResponse();
        return done("answerDuel", accept, ActionResult.OK);
    }

    @Override
    public ActionResult answerPledge(boolean accept) {
        final Player p = r.player();
        if ((p == null) || (p.getRequest() == null)) {
            return done("answerPledge", accept, ActionResult.INVALID);
        }
        final Player requestor = p.getRequest().getPartner();
        if (requestor == null) {
            return done("answerPledge", accept, ActionResult.INVALID);
        }
        // stock RequestAnswerJoinPledge
        if (!accept) {
            final org.l2jmobius.gameserver.network.serverpackets.SystemMessage sm = new org.l2jmobius.gameserver.network.serverpackets.SystemMessage(
                org.l2jmobius.gameserver.network.SystemMessageId.S1_DID_NOT_RESPOND_INVITATION_TO_THE_CLAN_HAS_BEEN_CANCELLED);
            sm.addString(p.getName());
            requestor.sendPacket(sm);
            p.getRequest().onRequestResponse();
            return done("answerPledge", accept, ActionResult.OK);
        }
        if (!(requestor.getRequest().getRequestPacket() instanceof org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge req)) {
            p.getRequest().onRequestResponse();
            return done("answerPledge", accept, ActionResult.INVALID);
        }
        final org.l2jmobius.gameserver.entity.clan.Clan clan = requestor.getClan();
        if (clan == null) {
            p.getRequest().onRequestResponse();
            return done("answerPledge", accept, ActionResult.INVALID);
        }
        // leave bot-clan before stock join checks (traders/leaders already declined in brain)
        sink.prepareJoinPlayerClan(r, p);
        if (!clan.checkClanJoinCondition(requestor, p, req.getPledgeType())) {
            p.getRequest().onRequestResponse();
            return done("answerPledge", accept, ActionResult.INVALID);
        }
        p.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.JoinPledge(requestor.getClanId()));
        p.setPledgeType(req.getPledgeType());
        if (req.getPledgeType() == org.l2jmobius.gameserver.entity.clan.Clan.SUBUNIT_ACADEMY) {
            p.setPowerGrade(9);
            p.setLvlJoinedAcademy(p.getLevel());
        } else {
            p.setPowerGrade(5);
        }
        clan.addClanMember(p);
        p.setClanPrivileges(p.getClan().getRankPrivs(p.getPowerGrade()));
        p.sendPacket(org.l2jmobius.gameserver.network.SystemMessageId.ENTERED_THE_CLAN);
        final org.l2jmobius.gameserver.network.serverpackets.SystemMessage joined = new org.l2jmobius.gameserver.network.serverpackets.SystemMessage(
            org.l2jmobius.gameserver.network.SystemMessageId.S1_HAS_JOINED_THE_CLAN);
        joined.addString(p.getName());
        clan.broadcastToOnlineMembers(joined);
        clan.broadcastToOtherOnlineMembers(new org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListAdd(p), p);
        clan.broadcastToOnlineMembers(new org.l2jmobius.gameserver.network.serverpackets.PledgeShowInfoUpdate(clan));
        p.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListAll(clan, p));
        p.setClanJoinExpiryTime(0);
        p.broadcastUserInfo();
        p.getRequest().onRequestResponse();
        // phase 7e: leave bot-clan paper and pin as player-clan member (caller also marks via module)
        return done("answerPledge", accept, ActionResult.OK);
    }

    @Override
    public ActionResult inviteClan(String playerName, int pledgeType) {
        final Player p = r.player();
        final Player target = playerName == null ? null : World.getPlayer(playerName);
        final org.l2jmobius.gameserver.entity.clan.Clan clan = p == null ? null : p.getClan();
        if ((p == null) || (target == null) || (clan == null) || (clan.getLeaderId() != p.getObjectId())
            || (target.getClient() == null) || target.getClient().isDetached() || (target.getClan() != null)
            || !clan.checkClanJoinCondition(p, target, pledgeType)) {
            return done("inviteClan", playerName, ActionResult.INVALID);
        }
        // Stock AnswerJoinPledge requires requestor packet instanceof RequestJoinPledge.
        final org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge packet = newJoinPledgePacket(target.getObjectId(), pledgeType);
        if ((packet == null) || !p.getRequest().setRequest(target, packet)) {
            return done("inviteClan", playerName, ActionResult.INVALID);
        }
        final String sub = clan.getSubPledge(pledgeType) != null ? clan.getSubPledge(pledgeType).getName() : null;
        target.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.AskJoinPledge(p.getObjectId(), sub, pledgeType, clan.getName()));
        return done("inviteClan", target.getName() + ":" + pledgeType, ActionResult.OK);
    }

    private static org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge newJoinPledgePacket(int targetOid, int pledgeType) {
        try {
            final var p = org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge.class.getDeclaredConstructor().newInstance();
            final var t = org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge.class.getDeclaredField("_target");
            final var typ = org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge.class.getDeclaredField("_pledgeType");
            t.setAccessible(true);
            typ.setAccessible(true);
            t.setInt(p, targetOid);
            typ.setInt(p, pledgeType);
            return p;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public ActionResult sayClan(String text) {
        return channelSay("sayClan", ChatType.CLAN, text);
    }

    @Override
    public ActionResult leaveParty() {
        final Player p = r.player();
        final org.l2jmobius.gameserver.entity.groups.Party party = p == null ? null : p.getParty();
        if ((party == null) || (party.isInDimensionalRift() && !party.getDimensionalRift().getRevivedAtWaitingRoom().contains(p))) {
            return done("leaveParty", "", ActionResult.INVALID);
        }
        party.removePartyMember(p, org.l2jmobius.gameserver.entity.groups.PartyMessageType.LEFT); // stock RequestWithDrawalParty
        return done("leaveParty", "", ActionResult.OK);
    }

    @Override
    public ActionResult giveLeader(int objectId) {
        final Player p = r.player();
        final org.l2jmobius.gameserver.entity.groups.Party party = p == null ? null : p.getParty();
        if ((party == null) || !party.isLeader(p) || !(World.findObject(objectId) instanceof Player to) || !party.getMembers().contains(to)) {
            return done("giveLeader", objectId, ActionResult.INVALID);
        }
        party.changePartyLeader(to.getName()); // stock RequestChangePartyLeader
        return done("giveLeader", objectId, ActionResult.OK);
    }

    @Override
    public ActionResult sayParty(String text) {
        final Player p = r.player();
        if ((p == null) || !p.isInParty() || !okText(text)) {
            return done("sayParty", text, ActionResult.INVALID);
        }
        if (!speak(sink.now())) {
            return done("sayParty", text, ActionResult.COOLDOWN);
        }
        p.getParty().broadcastCreatureSay(new CreatureSay(p, ChatType.PARTY, p.getName(), text), p); // stock ChatParty
        return done("sayParty", text, ActionResult.OK);
    }

    @Override
    public boolean playerBlocksUs(String playerName) {
        final Player p = r.player();
        final Player them = playerName == null ? null : World.getPlayer(playerName);
        return (p != null) && (them != null)
            && org.l2jmobius.gameserver.entity.actor.holders.player.BlockList.isBlocked(them, p);
    }

    @Override
    public ActionResult whisper(String to, String text) {
        final Player p = r.player();
        final Player receiver = to == null ? null : World.getPlayer(to);
        if ((p == null) || (receiver == null) || (receiver.getClient() == null) || !okText(text)
            || org.l2jmobius.gameserver.entity.actor.holders.player.BlockList.isBlocked(receiver, p)) {
            return done("whisper", to, ActionResult.INVALID);
        }
        if (!speak(sink.now())) {
            return done("whisper", to, ActionResult.COOLDOWN);
        }
        receiver.sendPacket(new CreatureSay(p, ChatType.WHISPER, p.getName(), text)); // stock ChatWhisper (no client: no echo)
        return done("whisper", to + ": " + text, ActionResult.OK);
    }

    @Override
    public ActionResult sayShout(String text) {
        return channelSay("sayShout", ChatType.SHOUT, text);
    }

    @Override
    public ActionResult sayTrade(String text) {
        return channelSay("sayTrade", ChatType.TRADE, text);
    }

    /** Stock shout/trade handler (region or global as the server is set). */
    private ActionResult channelSay(String name, ChatType type, String text) {
        final Player p = alive();
        if ((p == null) || !okText(text)) {
            return done(name, text, ActionResult.INVALID);
        }
        if (!speak(sink.now())) {
            return done(name, text, ActionResult.COOLDOWN);
        }
        final org.l2jmobius.gameserver.handler.IChatHandler h = org.l2jmobius.gameserver.handler.ChatHandler.getInstance().getHandler(type);
        if (h == null) {
            return done(name, text, ActionResult.INVALID);
        }
        h.onChat(type, p, null, text); // stock ChatShout / ChatTrade
        return done(name, text, ActionResult.OK);
    }

    @Override
    public ActionResult inviteParty(String playerName) {
        final Player p = r.player();
        final Player target = playerName == null ? null : World.getPlayer(playerName);
        if ((p == null) || (target == null) || (target.getClient() == null) || target.getClient().isDetached()
            || target.isInParty() || (target == p)
            || org.l2jmobius.gameserver.entity.actor.holders.player.BlockList.isBlocked(target, p)
            || p.isProcessingRequest() || target.isProcessingRequest()) {
            return done("inviteParty", playerName, ActionResult.INVALID);
        }
        final org.l2jmobius.gameserver.entity.groups.Party party = p.getParty();
        if (party != null) {
            if (!party.isLeader(p) || (party.getMemberCount() >= 9)
                || (party.getPendingInvitation() && !party.isInvitationRequestExpired())) {
                return done("inviteParty", playerName, ActionResult.INVALID);
            }
            p.onTransactionRequest(target);
            target.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.AskJoinParty(p.getName(), party.getDistributionType()));
            party.setPendingInvitation(true); // stock addTargetToParty; player answers (no botPartyRequest)
            return done("inviteParty", playerName, ActionResult.OK);
        }
        final org.l2jmobius.gameserver.entity.groups.PartyDistributionType dist = p.getPartyDistributionType() == null
            ? org.l2jmobius.gameserver.entity.groups.PartyDistributionType.FINDERS_KEEPERS : p.getPartyDistributionType();
        target.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.AskJoinParty(p.getName(), dist));
        target.setActiveRequester(p);
        p.onTransactionRequest(target);
        p.setPartyDistributionType(dist); // stock createNewParty; player answers
        return done("inviteParty", playerName, ActionResult.OK);
    }

    @Override
    public ActionResult social(int id) {
        final Player p = alive();
        if ((p == null) || (id < 2) || (id > 16) || p.isSitting() || p.isCastingNow() || p.isAttackingNow() || p.isMoving()) {
            return done("social", id, ActionResult.INVALID);
        }
        p.broadcastPacket(new org.l2jmobius.gameserver.network.serverpackets.SocialAction(p.getObjectId(), id)); // stock RequestSocialAction
        return done("social", id, ActionResult.OK);
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

    @Override
    public Pos nearestGk() {
        final GkGraph g = sink.gkGraph();
        final Player p = r.player();
        if ((g == null) || (p == null)) {
            return null;
        }
        return g.nearestGk(new Pos(p.getX(), p.getY(), p.getZ()));
    }

    @Override
    public ActionResult teleportViaGk(Pos dest) {
        final Player p = alive();
        if ((p == null) || (dest == null)) {
            return done("teleportViaGk", dest, ActionResult.INVALID);
        }
        final Teleporter npc = World.getFirstVisibleObjectInRange(p, Teleporter.class, GK_RANGE);
        if (npc == null) {
            return done("teleportViaGk", dest, ActionResult.NOT_REACHABLE);
        }
        final GkGraph graph = sink.gkGraph();
        final TeleportHolder holder = TeleporterData.getInstance().getHolder(npc.getId(), TeleportType.NORMAL.name());
        if (holder == null) {
            return done("teleportViaGk", dest, ActionResult.INVALID);
        }
        final int locId;
        if (graph != null) {
            final GkGraph.Hop hop = graph.nextHop(npc.getId(), dest);
            if (hop == null) {
                return done("teleportViaGk", "already", ActionResult.OK); // right town: brain runs to the mate
            }
            locId = hop.locId();
        } else {
            // no graph: pick the NORMAL destination closest to dest (still a stock GK landing)
            TeleportLocation best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (TeleportLocation loc : holder.getLocations()) {
                final double d = Math.hypot(loc.getX() - dest.x(), loc.getY() - dest.y());
                if (d < bestD) {
                    bestD = d;
                    best = loc;
                }
            }
            if (best == null) {
                return done("teleportViaGk", dest, ActionResult.INVALID);
            }
            locId = best.getId();
        }
        final TeleportLocation loc = holder.getLocation(locId);
        if (loc == null) {
            return done("teleportViaGk", dest, ActionResult.INVALID);
        }
        if (p.isSitting()) {
            p.standUp();
        }
        p.abortAttack();
        p.abortCast();
        p.getAI().setIntentionActive();
        // stock GK destination (not the player's coordinates); fee skipped — bots have no client wallet for this path
        p.teleToLocation(loc, true);
        return done("teleportViaGk", loc.getX() + "," + loc.getY(), ActionResult.OK);
    }
}
