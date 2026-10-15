package org.l2start.bots.chat;

import java.util.Locale;
import java.util.Map;

/**
 * Phase 6e: short class names for whisper replies ({@code {class}}). Pure map — no Mobius types.
 * Unknown ids fall back to the LFP role word (heal/buff/dd).
 */
public final class ClassLabels {
    private static final Map<Integer, String> EN = Map.ofEntries(
        e(0, "Fighter"), e(1, "Warrior"), e(2, "Glad"), e(3, "Warlord"), e(88, "Duelist"), e(89, "Dreadnought"),
        e(4, "Knight"), e(5, "Paladin"), e(6, "DK"), e(90, "Phoenix"), e(91, "Hell Knight"),
        e(7, "Rogue"), e(8, "TH"), e(9, "Hawkeye"), e(92, "Adventurer"), e(93, "Saggitarius"),
        e(10, "Mystic"), e(11, "Wizard"), e(12, "Sorcerer"), e(13, "Necro"), e(14, "Warlock"),
        e(94, "Archmage"), e(95, "Soultaker"), e(96, "Arcana Lord"),
        e(15, "Cleric"), e(16, "Bishop"), e(17, "Prophet"), e(97, "Cardinal"), e(98, "Apostle"),
        e(18, "Elven Fighter"), e(19, "Elven Knight"), e(20, "Temple"), e(21, "SWS"), e(99, "Eva Templar"),
        e(100, "Sword Muse"), e(22, "Scout"), e(23, "Plainswalker"), e(24, "Silver Ranger"),
        e(101, "Wind Rider"), e(102, "Moonlight"),
        e(25, "Elven Mystic"), e(26, "Elven Wizard"), e(27, "Spellsinger"), e(28, "EE"),
        e(103, "Mystic Muse"), e(104, "Elemental"), e(29, "Oracle"), e(30, "Elder"), e(105, "Eva Saint"),
        e(31, "Dark Fighter"), e(32, "Palus"), e(33, "Shillien Knight"), e(34, "BD"), e(106, "Shillien Templar"),
        e(107, "Spectral Dancer"), e(35, "Assassin"), e(36, "Abyss"), e(37, "Phantom Ranger"),
        e(108, "Ghost Hunter"), e(109, "Ghost Ranger"),
        e(38, "Dark Mystic"), e(39, "Dark Wizard"), e(40, "Spellhowler"), e(41, "Phantom Summoner"),
        e(110, "Storm Screamer"), e(111, "Spectral Master"), e(42, "Shillien Oracle"), e(43, "SE"),
        e(112, "Shillien Saint"),
        e(44, "Orc Fighter"), e(45, "Orc Raider"), e(46, "Destroyer"), e(113, "Titan"),
        e(47, "Monk"), e(48, "Tyrant"), e(114, "Grand Khavatari"),
        e(49, "Orc Mystic"), e(50, "Orc Shaman"), e(51, "Overlord"), e(52, "Warcryer"),
        e(115, "Dominator"), e(116, "Doomcryer"),
        e(53, "Dwarven Fighter"), e(54, "Scavenger"), e(55, "Bounty"), e(117, "Fortune Seeker"),
        e(56, "Artisan"), e(57, "Warsmith"), e(118, "Maestro"));

    private static final Map<Integer, String> RU = Map.ofEntries(
        e(0, "воин"), e(1, "воин"), e(2, "глад"), e(3, "варлорд"), e(88, "дуэлист"), e(89, "дредноут"),
        e(4, "рыцарь"), e(5, "паладин"), e(6, "тёмный рыцарь"), e(90, "феникс"), e(91, "хелл найт"),
        e(7, "рог"), e(8, "тха"), e(9, "хавик"), e(92, "адвенчер"), e(93, "сагитарий"),
        e(10, "мистик"), e(11, "виз"), e(12, "сорк"), e(13, "некр"), e(14, "варлок"),
        e(94, "архмаг"), e(95, "соултейкер"), e(96, "аркана"),
        e(15, "клерик"), e(16, "епископ"), e(17, "пророк"), e(97, "кардинал"), e(98, "апостол"),
        e(18, "эльф"), e(19, "эльф рыцарь"), e(20, "темпл"), e(21, "свс"), e(99, "ева темпл"),
        e(100, "сва"), e(22, "скаут"), e(23, "плейн"), e(24, "сильвер"), e(101, "винд райдер"),
        e(102, "мунлайт"),
        e(25, "эльф маг"), e(26, "эльф виз"), e(27, "спелсингер"), e(28, "ее"), e(103, "мистик муза"),
        e(104, "элементал"), e(29, "оракул"), e(30, "эльд"), e(105, "ева святая"),
        e(31, "тёмный эльф"), e(32, "палус"), e(33, "шк"), e(34, "бд"), e(106, "шк темпл"),
        e(107, "спектрал"), e(35, "ассасин"), e(36, "абисс"), e(37, "фантом рейндж"),
        e(108, "гост хантер"), e(109, "гост рейндж"),
        e(38, "тёмный маг"), e(39, "тёмный виз"), e(40, "спелхаулер"), e(41, "фантом сум"),
        e(110, "шторм"), e(111, "спектрал мастер"), e(42, "шо"), e(43, "се"), e(112, "шс"),
        e(44, "орк"), e(45, "рейдер"), e(46, "дестр"), e(113, "титан"),
        e(47, "монах"), e(48, "тирант"), e(114, "каватари"),
        e(49, "орк маг"), e(50, "шаман"), e(51, "ол"), e(52, "вц"), e(115, "домик"), e(116, "думкрайер"),
        e(53, "гном"), e(54, "скав"), e(55, "баунти"), e(117, "форчун"), e(56, "артизан"),
        e(57, "варсмит"), e(118, "маэстро"));

    private ClassLabels() {
    }

    /** Short class name in RU or EN; unknown → heal/buff/dd role word. */
    public static String name(int classId, String lang) {
        final boolean en = lang != null && lang.toUpperCase(Locale.ROOT).startsWith("EN");
        final String n = (en ? EN : RU).get(classId);
        if (n != null) {
            return n;
        }
        return switch (LfpParse.roleOf(classId)) {
            case HEAL -> en ? "heal" : "хил";
            case BUFF -> en ? "buff" : "баф";
            default -> en ? "dd" : "дд";
        };
    }

    private static Map.Entry<Integer, String> e(int id, String name) {
        return Map.entry(id, name);
    }
}
