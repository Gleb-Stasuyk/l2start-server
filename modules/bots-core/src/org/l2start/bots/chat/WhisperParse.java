package org.l2start.bots.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phase 6e: parse a player's whisper into an intent (Living Worlds FakePlayerChatData idea:
 * {@code EQUALS} / {@code STARTS_WITH} / {@code CONTAINS}, RU + EN). Pure: no game types, unit-tested on a plain JDK.
 * Party-ask lines stay with {@link LfpParse}; this parser still returns {@link Intent#ASK_PARTY} when they match.
 */
public final class WhisperParse {
    /** What the whisper is about. */
    public enum Intent {
        GREET, BYE, THANKS, SORRY, HOW_ARE_YOU, ASK_LEVEL, ASK_CLASS, ASK_SPOT, WHERE_TO_HUNT, ASK_PARTY, ASK_RES,
        ASK_BUFF, ASK_CLAN, INSULT, UNKNOWN
    }

    /** How a rule matches the normalized text (LW searchMethod). */
    public enum Match {
        /** Whole line equals one of the phrases. */
        EQUALS,
        /** Line starts with one of the phrases. */
        STARTS_WITH,
        /** Line contains every token of one phrase group (tokens split by {@code ;}). */
        CONTAINS
    }

    private record Rule(Match how, Intent intent, String[] phrases) {
    }

    /** Cyrillic → treat as Russian for reply language. */
    private static final Pattern CYRILLIC = Pattern.compile("[а-яА-ЯёЁ]");

    /** Caps-lock / flood: mostly Latin uppercase letters, long enough to ignore. */
    private static final Pattern LATIN_LETTER = Pattern.compile("[A-Za-z]");

    /**
     * Rules in priority order: EQUALS first (exact), then STARTS_WITH, then CONTAINS. First hit wins.
     * CONTAINS phrases use {@code ;} between required tokens (LW style).
     */
    private static final List<Rule> RULES = buildRules();

    private WhisperParse() {
    }

    /** Parse a whisper; never null ({@link Intent#UNKNOWN} when nothing matches). */
    public static Intent parse(String text) {
        if (text == null || text.isBlank() || isFlood(text)) {
            return Intent.UNKNOWN;
        }
        final String t = norm(text).trim();
        if (t.isEmpty()) {
            return Intent.UNKNOWN;
        }
        for (Rule r : RULES) {
            if (hits(r, t)) {
                return r.intent;
            }
        }
        return Intent.UNKNOWN;
    }

    /**
     * Language of the whisper for the reply: {@code RU} if it has Cyrillic, else {@code EN}.
     * The brain may still fall back to the persona language when the persona does not know that one.
     */
    public static String langOf(String text) {
        return text != null && CYRILLIC.matcher(text).find() ? "RU" : "EN";
    }

    /**
     * Phase 6g: «отстань» / «не пиши» / stop spam — the bot tags {@code ANNOYED} and stays silent on whispers for a day.
     */
    public static boolean isLeaveAlone(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        final String t = norm(text);
        return t.contains("отстань") || t.contains("не пиши") || t.contains("stop spam") || t.contains("fuck off")
            || t.equals("отвали") || t.contains("shut up");
    }

    /** Caps-lock spam or empty junk — ignore (UNKNOWN, brain stays silent). */
    public static boolean isFlood(String text) {
        if (text == null) {
            return true;
        }
        final String s = text.strip();
        if (s.length() < 8) {
            return false;
        }
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (Character.isLetter(c) && LATIN_LETTER.matcher(String.valueOf(c)).matches()) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters >= 8 && upper * 10 >= letters * 8; // ≥80 % Latin uppercase
    }

    static boolean hits(Rule r, String t) {
        return switch (r.how) {
            case EQUALS -> {
                for (String p : r.phrases) {
                    if (t.equals(p)) {
                        yield true;
                    }
                }
                yield false;
            }
            case STARTS_WITH -> {
                for (String p : r.phrases) {
                    if (t.startsWith(p)) {
                        yield true;
                    }
                }
                yield false;
            }
            case CONTAINS -> {
                for (String group : r.phrases) {
                    boolean all = true;
                    for (String word : group.split(";")) {
                        final String w = word.trim();
                        if (!w.isEmpty() && !t.contains(w)) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    private static String norm(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[!?.,…]+$", "").trim();
    }

    private static List<Rule> buildRules() {
        final List<Rule> r = new ArrayList<>();
        // ---- EQUALS (whole line)
        r.add(eq(Intent.GREET, "hi", "hey", "hello", "yo", "sup", "привет", "прив", "ку", "хай", "здарова", "здравствуй", "здравствуйте", "qq", "ку ку"));
        r.add(eq(Intent.BYE, "bye", "bb", "cya", "goodbye", "пока", "бб", "удачи", "бывай", "всего"));
        r.add(eq(Intent.THANKS, "thanks", "thank you", "ty", "thx", "tyvm", "спс", "спасибо", "благодарю", "сенкс"));
        r.add(eq(Intent.SORRY, "sorry", "sry", "my bad", "сорри", "извини", "извините", "прости"));
        r.add(eq(Intent.HOW_ARE_YOU, "how are you", "how r you", "how are u", "как дела", "как ты", "как сам", "как жизнь"));
        r.add(eq(Intent.ASK_LEVEL, "lvl?", "level?", "ур?", "лвл?", "сколько лвл", "какой лвл", "какой уровень"));
        r.add(eq(Intent.ASK_CLASS, "class?", "класс?", "кем?", "кто ты"));
        r.add(eq(Intent.ASK_SPOT, "где?", "where?", "где ты", "где качаешься"));
        r.add(eq(Intent.ASK_PARTY, "+", "+1", "я", "me", "go", "го", "да", "yes", "ok", "ок", "invite", "inv", "инвайт", "пати", "pt"));
        // ---- STARTS_WITH
        r.add(sw(Intent.HOW_ARE_YOU, "how are you", "how r you", "how are u", "как дела", "как ты", "как сам"));
        r.add(sw(Intent.ASK_LEVEL, "what level", "whats your level", "what's your level", "what lvl", "какой у тебя лвл", "какой у тебя уровень",
            "сколько тебе", "а сколько тебе", "какой лвл", "какой уровень"));
        r.add(sw(Intent.ASK_CLASS, "what class", "whats your class", "what's your class", "which class", "какой класс", "кем играешь", "ты кто по классу"));
        r.add(sw(Intent.ASK_SPOT, "where are you", "where you", "where do you hunt", "where u hunt", "где ты", "где качаешься", "где фармишь", "где сидишь"));
        r.add(sw(Intent.WHERE_TO_HUNT, "where to hunt", "where should i", "куда качаться", "куда идти", "куда фармить", "где лучше качаться", "куда лучше"));
        r.add(sw(Intent.ASK_RES, "can you res", "pls res", "res me", "rez me", "заресай", "ресани", "ресни", "подресай", "можно рес"));
        r.add(sw(Intent.ASK_BUFF, "buff me", "pls buff", "buff pls", "бафни", "бафни меня", "бафани", "можно баф", "кинь баф"));
        r.add(sw(Intent.ASK_PARTY, "can i join", "invite me", "party?", "можно к вам", "го пати", "кинь пати", "возьми в пати", "take me"));
        r.add(sw(Intent.ASK_CLAN, "invite clan", "clan invite", "join clan", "can i join clan", "в клан", "инвайт в клан", "можно в клан", "возьми в клан",
            "academy", "в академию", "можно в академию"));
        r.add(ct(Intent.ASK_CLAN, "join;clan", "clan;please", "в;клан", "клан;инвайт"));
        r.add(sw(Intent.GREET, "hi ", "hey ", "hello ", "привет ", "прив ", "ку ", "здарова "));
        r.add(sw(Intent.BYE, "bye ", "bb ", "пока ", "бб "));
        r.add(sw(Intent.THANKS, "thanks ", "thank ", "ty ", "спс ", "спасибо "));
        r.add(sw(Intent.SORRY, "sorry ", "сорри ", "извини "));
        // ---- CONTAINS (all tokens of a group)
        r.add(ct(Intent.INSULT, "fuck", "shit;you", "noob", "nub", "idiot", "moron", "suck", "пидор", "блядь", "сука", "мусор", "ноуб", "нуб", "идиот", "дебил", "урод", "отвали",
            "отстань", "не пиши", "stop spam", "fuck off", "shut up"));
        r.add(ct(Intent.ASK_LEVEL, "level;you", "lvl;you", "your;lvl", "your;level", "сколько;лвл", "какой;лвл", "какой;уровень", "сколько;уровень"));
        r.add(ct(Intent.ASK_CLASS, "what;class", "your;class", "какой;класс", "кем;игра"));
        r.add(ct(Intent.ASK_SPOT, "where;hunt", "where;farm", "где;кача", "где;фарм", "где;сидишь"));
        r.add(ct(Intent.WHERE_TO_HUNT, "where;hunt", "куда;кача", "куда;фарм", "где;лучше"));
        r.add(ct(Intent.ASK_PARTY, "party", "invite", "пати", "инвайт", "можно;вам", "го;пати"));
        r.add(ct(Intent.ASK_RES, "res", "rez", "рес"));
        r.add(ct(Intent.ASK_BUFF, "buff", "баф"));
        r.add(ct(Intent.HOW_ARE_YOU, "how;are;you", "как;дела"));
        r.add(ct(Intent.GREET, "hello", "привет"));
        r.add(ct(Intent.THANKS, "thank", "спасибо", "thanks"));
        r.add(ct(Intent.BYE, "goodbye", "пока"));
        return List.copyOf(r);
    }

    private static Rule eq(Intent i, String... phrases) {
        return new Rule(Match.EQUALS, i, phrases);
    }

    private static Rule sw(Intent i, String... phrases) {
        return new Rule(Match.STARTS_WITH, i, phrases);
    }

    private static Rule ct(Intent i, String... phrases) {
        return new Rule(Match.CONTAINS, i, phrases);
    }
}
