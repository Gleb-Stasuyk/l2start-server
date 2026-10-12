package org.l2start.rates.floating;

/** How rates-floating treats an item of a drop. */
public enum Kind {
    /** Exactly x AdenaRate, only from the stock roll. */
    ADENA,
    /** Herbs and anything listed "keep": stock, only from the stock roll. */
    STOCK,
    /** Weapons, armour, jewellery, recipes, enchant scrolls, life stones, soul crystals, collars, exact-items.xml: always 1, chance floats. */
    EXACT,
    /** Stackable resources, materials, crystals, shots, ...: chance and amount float. */
    STACK
}
