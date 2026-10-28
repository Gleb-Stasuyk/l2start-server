package org.l2start.rates.floating;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.item.Armor;
import org.l2jmobius.gameserver.entity.item.EtcItem;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.Weapon;
import org.l2jmobius.gameserver.entity.item.type.EtcItemType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;

/** The kind of an item by its stock template and exact-items.xml (cached). */
public final class ItemKinds {
    private final ItemRules rules;
    private final Map<Integer, Kind> cache = new ConcurrentHashMap<>();

    public ItemKinds(ItemRules rules) {
        this.rules = rules;
    }

    public Kind of(int itemId) {
        return cache.computeIfAbsent(itemId, this::classify);
    }

    private Kind classify(int id) {
        if (id == Inventory.ADENA_ID) {
            return Kind.ADENA;
        }
        if (rules.keep.contains(id)) {
            return Kind.STOCK;
        }
        final ItemTemplate t = ItemData.getInstance().getTemplate(id);
        if (t == null) {
            return Kind.STOCK;
        }
        return classify(id, t, rules);
    }

    static Kind classify(int id, ItemTemplate t, ItemRules rules) {
        if (t.hasExImmediateEffect() || t.isQuestItem()) {
            return Kind.STOCK; // herbs, quest items (their own multiplier)
        }
        if (rules.exact.contains(id) || rules.exactName(t.getName()) || (t instanceof Weapon) || (t instanceof Armor)) {
            return Kind.EXACT;
        }
        if (t instanceof EtcItem etc) {
            final EtcItemType type = etc.getItemType();
            if ((type == EtcItemType.RECIPE) || (type == EtcItemType.SCRL_ENCHANT_WP) || (type == EtcItemType.SCRL_ENCHANT_AM) || (type == EtcItemType.BLESS_SCRL_ENCHANT_WP)
                || (type == EtcItemType.BLESS_SCRL_ENCHANT_AM) || (type == EtcItemType.PET_COLLAR)) {
                return Kind.EXACT;
            }
        }
        return t.isStackable() ? Kind.STACK : Kind.EXACT;
    }

    public int questX1Listed() {
        return rules.questX1.size();
    }

    /** Quest reward kept x1 (valuable chain rewards and every EXACT item). */
    public boolean questX1(int itemId) {
        return rules.questX1.contains(itemId) || (of(itemId) == Kind.EXACT);
    }
}
