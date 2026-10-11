package org.l2start.bots.brain.basic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * data/playstyles.xml (phase 4c, from Living Worlds PhantomPlaystyles.xml): per class lineage, the skills a bot uses in
 * a fight, what for (use) and when (conditions). Pure data; {@link PlaystyleEngine} picks the skill.
 */
public final class Playstyles {
    public enum Use {
        PANIC, LIMIT, OPENER, STANCE, CONTROL, DEBUFF, AOE, ROTATION, PULL
    }

    public enum Cond {
        ALWAYS, TARGET_HP_BELOW, TARGET_HP_ABOVE, SELF_HP_BELOW, MP_ABOVE, MOBS_NEAR, MOBS_UNSPOILED, REAR, CHARGES, CHARGES_BELOW, DEBUFF_MISSING,
        SELF_ABNORMAL_FREE, NOT_SPOILED, DURABLE_TARGET, HEALER_READY, ONCE_PER_TARGET, UNDER_ATTACK, PVP, FOCUS_ON_ME,
        /** l2start: the target is undead (holy skills, Turn Undead); not in LW. */
        TARGET_UNDEAD, UNKNOWN
    }

    /** One skill line; numbers not given are 0 (levels: 0..999). */
    public record Entry(int skillId, String name, Use use, Set<Cond> when, double mpAbove, double hpBelow, double hpAbove, double selfHpBelow, int mobsAtLeast,
        int minLevel, int maxLevel, long paceMs, int order, int chargesAtLeast, int chargesBelow) {
        /** Without charges (before phase 4c, second part). */
        public Entry(int skillId, String name, Use use, Set<Cond> when, double mpAbove, double hpBelow, double hpAbove, double selfHpBelow, int mobsAtLeast, int minLevel,
            int maxLevel, long paceMs, int order) {
            this(skillId, name, use, when, mpAbove, hpBelow, hpAbove, selfHpBelow, mobsAtLeast, minLevel, maxLevel, paceMs, order, 0, 0);
        }

        public boolean fitsLevel(int level) {
            return (level >= minLevel) && (level <= maxLevel);
        }
    }

    /** @param role null = any (else ARCHER, DAGGER, ...: tells apart lineages sharing a class id) */
    public record Playstyle(String name, Set<Integer> classIds, String role, List<Entry> entries) {
    }

    public static final Playstyles EMPTY = new Playstyles(List.of());

    private final List<Playstyle> all;

    public Playstyles(List<Playstyle> all) {
        this.all = List.copyOf(all);
    }

    public List<Playstyle> all() {
        return all;
    }

    /**
     * The playstyle of a class: a role-specific one wins (a bow = ARCHER, otherwise DAGGER for the shared rogue ids),
     * else the role-agnostic one.
     * @return null when the class has none (support classes, summoners)
     */
    public Playstyle of(int classId, boolean bow) {
        Playstyle any = null;
        for (Playstyle p : all) {
            if (!p.classIds().contains(classId)) {
                continue;
            }
            if (p.role() == null) {
                any = any == null ? p : any;
            } else if (bow ? "ARCHER".equals(p.role()) : !"ARCHER".equals(p.role())) {
                return p;
            }
        }
        return any;
    }

    public static Playstyles load(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            final NodeList nodes = f.newDocumentBuilder().parse(in).getDocumentElement().getElementsByTagName("playstyle");
            final List<Playstyle> l = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                final Element e = (Element) nodes.item(i);
                final Set<Integer> ids = new HashSet<>();
                for (String s : e.getAttribute("classIds").split(",")) {
                    if (!s.isBlank()) {
                        ids.add(Integer.parseInt(s.trim()));
                    }
                }
                final List<Entry> entries = new ArrayList<>();
                final NodeList skills = e.getElementsByTagName("skill");
                for (int j = 0; j < skills.getLength(); j++) {
                    final Element s = (Element) skills.item(j);
                    final Set<Cond> when = EnumSet.noneOf(Cond.class);
                    for (String c : s.getAttribute("when").split(",")) {
                        if (!c.isBlank()) {
                            when.add(cond(c.trim()));
                        }
                    }
                    entries.add(new Entry(Integer.parseInt(s.getAttribute("id")), s.getAttribute("name"), Use.valueOf(s.getAttribute("use").trim().toUpperCase(Locale.ROOT)), when,
                        num(s, "mpAbove", 0), num(s, "hpBelow", 0), num(s, "hpAbove", 0), num(s, "selfHpBelow", 0), (int) num(s, "mobsAtLeast", 0), (int) num(s, "minLevel", 0),
                        (int) num(s, "maxLevel", 999), (long) num(s, "paceMs", -1), j, (int) num(s, "chargesAtLeast", 0), (int) num(s, "chargesBelow", 0)));
                }
                final String role = e.getAttribute("role").isBlank() ? null : e.getAttribute("role").trim().toUpperCase(Locale.ROOT);
                l.add(new Playstyle(e.getAttribute("name"), Set.copyOf(ids), role, List.copyOf(entries)));
            }
            return new Playstyles(l);
        }
    }

    private static Cond cond(String s) {
        try {
            return Cond.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Cond.UNKNOWN;
        }
    }

    private static double num(Element e, String attr, double def) {
        final String v = e.getAttribute(attr);
        return v.isBlank() ? def : Double.parseDouble(v.trim());
    }
}
