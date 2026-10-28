package org.l2start.bots.chat;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 6d: parse LFP/LFM lines and party-ask whispers (Living Worlds FakePlayerChatParsing idea, RU + EN). Pure:
 * no game types, unit-tested on a plain JDK.
 */
public final class LfpParse {
    /** What a party call asks for. */
    public enum Role {
        ANY, HEAL, BUFF, DD
    }

    /** A parsed party call (shout/trade/all), or null when the line is not one. */
    public record Call(Role role, int level, boolean lookingForMore) {
    }

    // UNICODE_CHARACTER_CLASS so \\b works with Cyrillic (ищу / хил / пати)
    private static final int RX = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    // English triggers + Russian (ищу пати / лфп / нужен хил ...)
    private static final Pattern LFP = Pattern.compile(
        "(?:\\b(?:lfm|lfp|lfg|lf)\\b|looking\\s+for|wanna\\s+pt|party\\s+up|need\\s+(?:heal|buff|dd|pt|party)|"
            + "ищу\\s+(?:пати|хил|хила|баф|бафера|дд|дпс)|нуж(?:ен|на|ны)\\s+(?:пати|хил|баф|дд)|"
            + "лфп|лфм|го\\s+пати|набер(?:у|ём|ем)\\s+пати|кто\\s+на\\s+(?:пати|фарм))",
        RX);

    private static final Pattern LFM = Pattern.compile("\\b(?:lfm|looking\\s+for\\s+more|нужен\\s+(?:ещ[её]|кто)|ещ[её]\\s+(?:нужен|надо))\\b", RX);

    private static final Pattern LEVEL = Pattern.compile("(?:(?:level|lvl|lv|ур(?:овень)?|лвл?)\\s*\\.?\\s*)?(\\d{1,2})\\s*\\+?", RX);

    private static final Pattern HEAL = Pattern.compile(
        "\\b(?:heal(?:er)?|bishop|ee|se|шэ|се|ее|хил(?:ер|а|ы)?|епископ|elder|oracle)\\b", RX);

    private static final Pattern BUFF = Pattern.compile(
        "\\b(?:buff(?:er)?|bd|sws|swordsinger|bladedancer|prophet|pp|wc|ол|баф(?:ер|ера|ы|а)?|пророк|варкраер|оверлорд|бд|свс|пп)\\b", RX);

    private static final Pattern DD = Pattern.compile(
        "\\b(?:dd|dps|tank|spoiler|dagger|archer|mage|wiz(?:ard)?|дд|дпс|танк|спойлер|маг|лук|даг)\\b", RX);

    /** Whisper that asks to join / invites: "+", "я", "можно к вам?", "invite", "инвайт". */
    private static final Pattern ASK_YES = Pattern.compile(
        "^(?:\\+|\\+1|я|me|go|го|да|yes|ok|ок|можно|can\\s*i|invite|inv|инвайт|инвайть|кинь\\s*пати|кидай|возьми|take\\s*me|pt\\s*pls?|пати)\\s*[!.?]?$",
        RX);

    private static final Pattern ASK_PARTY = Pattern.compile(
        "\\b(?:party|pt|grp|group|inv(?:ite)?|join|duo|lfm|lfp|lfg|хил|баф|пати|фарм|farm|hunt|можно\\s+к\\s+вам|го\\s+пати|кинь)\\b",
        RX);

    /** Interlude healer class ids (cleric line + elders). */
    private static final Set<Integer> HEALERS = Set.of(15, 16, 29, 30, 42, 43, 97, 98, 105, 112);
    /** Buffer / dance-song / warcryer / overlord / prophet. */
    private static final Set<Integer> BUFFERS = Set.of(17, 21, 28, 34, 51, 52, 100, 107, 115, 116);

    private LfpParse() {
    }

    /** @return true if the text reads like a party call. */
    public static boolean looksLikeLfp(String text) {
        return text != null && LFP.matcher(norm(text)).find();
    }

    /** Parse a shout/trade/all party call; null when it is not one. */
    public static Call parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        final String t = norm(text);
        if (!LFP.matcher(t).find()) {
            return null;
        }
        return new Call(roleIn(t), levelIn(t), LFM.matcher(t).find());
    }

    /** Role of a class id for matching LFP asks. */
    public static Role roleOf(int classId) {
        if (HEALERS.contains(classId)) {
            return Role.HEAL;
        }
        if (BUFFERS.contains(classId)) {
            return Role.BUFF;
        }
        return Role.DD;
    }

    /** Does this bot's role fit what the call asked for? */
    public static boolean fits(Role wanted, int classId) {
        if (wanted == null || wanted == Role.ANY) {
            return true;
        }
        return roleOf(classId) == wanted;
    }

    /** Level gap is ok for party search (same rule as PartyDecision: within 9, prefer ≤5). */
    public static boolean levelOk(int botLevel, int askLevel) {
        if (askLevel <= 0) {
            return true;
        }
        return Math.abs(botLevel - askLevel) <= 9;
    }

    /** Whisper that brings up partying (opens the agreed window / may lead to an invite). */
    public static boolean isPartyAsk(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        final String t = norm(text).trim();
        return ASK_YES.matcher(t).matches() || ASK_PARTY.matcher(t).find();
    }

    /** Short yes to a bot's LFP ad ("+", "я", "инвайт"): the bot should invite. */
    public static boolean isPartyYes(String text) {
        return text != null && ASK_YES.matcher(norm(text).trim()).matches();
    }

    static int levelIn(String t) {
        final Matcher m = LEVEL.matcher(t);
        int best = 0;
        while (m.find()) {
            final int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= 80) {
                best = n; // last plausible level wins ("LFP 40 heal 38" → 38 is rare; usually one)
            }
        }
        return best;
    }

    static Role roleIn(String t) {
        final boolean h = HEAL.matcher(t).find();
        final boolean b = BUFF.matcher(t).find();
        final boolean d = DD.matcher(t).find();
        if (h && !b && !d) {
            return Role.HEAL;
        }
        if (b && !h && !d) {
            return Role.BUFF;
        }
        if (d && !h && !b) {
            return Role.DD;
        }
        if (h) {
            return Role.HEAL; // "heal or dd" → prefer heal match for supports
        }
        if (b) {
            return Role.BUFF;
        }
        if (d) {
            return Role.DD;
        }
        return Role.ANY;
    }

    private static String norm(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
