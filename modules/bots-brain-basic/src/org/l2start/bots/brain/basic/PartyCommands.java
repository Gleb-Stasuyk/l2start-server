package org.l2start.bots.brain.basic;

import java.util.Locale;

/**
 * Phase 6c: commands of a real player in the party chat (Living Worlds PhantomPartyCommandRules, plus Russian).
 * Negations stop: "don't attack", "не бей" = STOP. Unknown text = NONE (the bot keeps doing what it does).
 */
public final class PartyCommands {
    public enum Command {
        NONE, STOP, FOLLOW, ASSIST, FREE_HUNT, BUFF, HEAL, SIT, RES, GIVE_LEAD, TAKE_LEAD, WAIT
    }

    private PartyCommands() {
    }

    public static Command parse(String raw) {
        if (raw == null) {
            return Command.NONE;
        }
        final String t = " " + raw.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[!?.,:;)(]+", " ").replaceAll("\\s+", " ").trim() + " ";
        if (t.isBlank() || (t.length() > 60)) {
            return Command.NONE; // a long line is talk, not a command
        }
        // negations of attacking first
        if (has(t, "не бей", "не атакуй", "не трогай", "не агрь", "dont attack", "don't attack", "do not attack", "dont pull", "hold fire", "не нападай")) {
            return Command.STOP;
        }
        if (has(t, "дай лидера", "дай лидер", "передай лидера", "give lead", "give me lead", "lead me", "pass lead")) {
            return Command.GIVE_LEAD;
        }
        if (has(t, "бери лидера", "возьми лидера", "будь лидером", "веди", "you lead", "take lead", "lead pls")) {
            return Command.TAKE_LEAD;
        }
        if (has(t, " жди ", "подожди", "ждите", " wait ", "wait for me", "иду в город", "щас вернусь", "сейчас вернусь", " brb ", " бб скоро ")) {
            return Command.WAIT;
        }
        if (has(t, " стоп ", " стой ", " стоять ", " stop ", " hold ", " stay ", " замри ")) {
            return Command.STOP;
        }
        if (has(t, "за мной", "иди сюда", "ко мне", "follow", " come ", "come here", " fol ", " идем ", " идём ", " пошли ", " го за мной ")) {
            return Command.FOLLOW;
        }
        if (has(t, "сам бей", "сам фарми", " фарми ", "бей сам", "free hunt", "farm ", " pull ", " пуль ", "kill everything", "бей всех")) {
            return Command.FREE_HUNT;
        }
        if (has(t, "бей мою", "бей моего", "мою цель", "асист", "ассист", " assist ", " asist ", "attack my", "my target", " бей ")) {
            return Command.ASSIST;
        }
        if (has(t, "бафни", "бафай", " баф ", "бафы", " buff ", "buff me", " bf ", " buffs ", " бф ")) {
            return Command.BUFF;
        }
        if (has(t, " хил ", "хильни", "полечи", " лечи ", " heal ", "heal me")) {
            return Command.HEAL;
        }
        if (has(t, " сядь ", " садись ", " отдыхаем ", " отдых ", " сели ", " sit ", " rest ", " relax ", " регеним ")) {
            return Command.SIT;
        }
        if (has(t, " рес ", "ресни", "воскреси", " res ", " rez ", "res me", " ress ")) {
            return Command.RES;
        }
        return Command.NONE;
    }

    private static boolean has(String t, String... keys) {
        for (String k : keys) {
            if (t.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
