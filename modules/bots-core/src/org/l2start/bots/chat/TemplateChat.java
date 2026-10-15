package org.l2start.bots.chat;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.l2start.bots.api.BotInfo;
import org.l2start.bots.api.Chat;

/** Phase 6a: the {@link Chat} of one bot on the shared {@link TemplateBrain}, humanized by its persona. */
public final class TemplateChat implements Chat {
    private final TemplateBrain brain;
    private final BotInfo info;
    private final Random rnd;

    public TemplateChat(TemplateBrain brain, BotInfo info, Random rnd) {
        this.brain = brain;
        this.info = info;
        this.rnd = rnd;
    }

    @Override
    public List<String> lines(String intent, Map<String, String> vars) {
        return lines(intent, vars, null);
    }

    @Override
    public List<String> lines(String intent, Map<String, String> vars, String lang) {
        final String use = replyLang(lang);
        return brain.pick(new ChatRequest(info.botId(), intent, "", use, info.chattiness(), "", List.of(), vars))
            .map(l -> Humanizer.style(l, care(info), rnd)).orElse(List.of());
    }

    /** Player language if the persona knows it (RU/EN/MIX); else persona language. */
    String replyLang(String playerLang) {
        if (playerLang == null || playerLang.isBlank()) {
            return info.lang();
        }
        final String p = playerLang.toUpperCase();
        final String mine = info.lang() == null ? "RU" : info.lang().toUpperCase();
        if ("MIX".equals(mine) || mine.startsWith(p) || p.startsWith(mine)) {
            return playerLang;
        }
        return info.lang();
    }

    @Override
    public long typingMs(String text) {
        return Humanizer.typingMs(text, info.reactionMs(), rnd);
    }

    static double care(BotInfo i) {
        return Math.max(0, Math.min(1, (i.skill() * 0.6) + ((1 - i.chattiness()) * 0.4)));
    }
}
