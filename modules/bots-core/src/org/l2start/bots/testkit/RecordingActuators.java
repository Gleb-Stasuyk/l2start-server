package org.l2start.bots.testkit;

import java.util.ArrayList;
import java.util.List;

import org.l2start.bots.api.ActionResult;
import org.l2start.bots.api.Actuators;
import org.l2start.bots.api.Pos;

/** Records every action (name + argument + result) and passes it on to another Actuators (or answers OK). */
public final class RecordingActuators implements Actuators {
    public record Call(String action, String arg, ActionResult result) {
        @Override
        public String toString() {
            return action + "(" + arg + ")=" + result;
        }
    }

    private final Actuators next;
    private final List<Call> calls = new ArrayList<>();

    public RecordingActuators(Actuators next) {
        this.next = next;
    }

    public List<Call> calls() {
        return calls;
    }

    public long count(String action) {
        return calls.stream().filter(c -> c.action.equals(action)).count();
    }

    public long countOk(String action) {
        return calls.stream().filter(c -> c.action.equals(action) && c.result.ok()).count();
    }

    public void clear() {
        calls.clear();
    }

    private ActionResult rec(String action, Object arg, java.util.function.Supplier<ActionResult> call) {
        final ActionResult r = next == null ? ActionResult.OK : call.get();
        calls.add(new Call(action, String.valueOf(arg), r));
        return r;
    }

    @Override public ActionResult moveTo(Pos to) { return rec("moveTo", to, () -> next.moveTo(to)); }
    @Override public ActionResult follow(int id) { return rec("follow", id, () -> next.follow(id)); }
    @Override public ActionResult setTarget(int id) { return rec("setTarget", id, () -> next.setTarget(id)); }
    @Override public ActionResult attack(int id) { return rec("attack", id, () -> next.attack(id)); }
    @Override public ActionResult cast(int skillId, int id) { return rec("cast", skillId + "->" + id, () -> next.cast(skillId, id)); }
    @Override public ActionResult useItem(int itemId) { return rec("useItem", itemId, () -> next.useItem(itemId)); }
    @Override public ActionResult pickUp(int id) { return rec("pickUp", id, () -> next.pickUp(id)); }
    @Override public ActionResult sit() { return rec("sit", "", next::sit); }
    @Override public ActionResult stand() { return rec("stand", "", next::stand); }
    @Override public ActionResult say(String text) { return rec("say", text, () -> next.say(text)); }
    @Override public ActionResult returnToTown() { return rec("returnToTown", "", next::returnToTown); }
    @Override public ActionResult setWalking(boolean walk) { return rec("setWalking", walk, () -> next.setWalking(walk)); }
    @Override public ActionResult stop() { return rec("stop", "", next::stop); }
    @Override public ActionResult petAttack(int id) { return rec("petAttack", id, () -> next.petAttack(id)); }
    @Override public ActionResult petFollow() { return rec("petFollow", "", next::petFollow); }
    @Override public ActionResult unsummon() { return rec("unsummon", "", next::unsummon); }
    @Override public ActionResult answerParty(boolean yes) { return rec("answerParty", yes, () -> next.answerParty(yes)); }
    @Override public ActionResult leaveParty() { return rec("leaveParty", "", next::leaveParty); }
    @Override public ActionResult giveLeader(int id) { return rec("giveLeader", id, () -> next.giveLeader(id)); }
    @Override public ActionResult sayParty(String text) { return rec("sayParty", text, () -> next.sayParty(text)); }
    @Override public ActionResult whisper(String to, String text) { return rec("whisper", to + ":" + text, () -> next.whisper(to, text)); }
    @Override public ActionResult sayShout(String text) { return rec("sayShout", text, () -> next.sayShout(text)); }
    @Override public ActionResult sayTrade(String text) { return rec("sayTrade", text, () -> next.sayTrade(text)); }
    @Override public ActionResult inviteParty(String name) { return rec("inviteParty", name, () -> next.inviteParty(name)); }
    @Override public ActionResult social(int id) { return rec("social", id, () -> next.social(id)); }

    @Override
    public boolean playerBlocksUs(String playerName) {
        return next != null && next.playerBlocksUs(playerName);
    }
    @Override public ActionResult logout() { return rec("logout", "", next::logout); }
    @Override public Pos nearestGk() { return next == null ? null : next.nearestGk(); }
    @Override public ActionResult teleportViaGk(Pos dest) { return rec("teleportViaGk", dest, () -> next.teleportViaGk(dest)); }

    @Override
    public int learnSkills() {
        final int n = next == null ? 0 : next.learnSkills();
        calls.add(new Call("learnSkills", "", ActionResult.OK));
        return n;
    }
}
