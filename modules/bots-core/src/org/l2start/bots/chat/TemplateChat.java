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
        return brain.pick(new ChatRequest(info.botId(), intent, "", info.lang(), info.chattiness(), "", List.of(), vars))
            .map(l -> Humanizer.style(l, care(info), rnd)).orElse(List.of());
    }

    @Override
    public long typingMs(String text) {
        return Humanizer.typingMs(text, info.reactionMs(), rnd);
    }

    static double care(BotInfo i) {
        return Math.max(0, Math.min(1, (i.skill() * 0.6) + ((1 - i.chattiness()) * 0.4)));
    }
}
