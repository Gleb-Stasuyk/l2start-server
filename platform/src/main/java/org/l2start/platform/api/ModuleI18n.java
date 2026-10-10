package org.l2start.platform.api;

import org.l2jmobius.gameserver.entity.actor.Player;

/** i18n/messages_&lt;lang&gt;.properties (UTF-8, MessageFormat). Fallback: player language, platform DefaultLanguage, en, key. */
public interface ModuleI18n {
    String get(Player player, String key, Object... args);

    String get(String lang, String key, Object... args);

    String lang(Player player);
}
