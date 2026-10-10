package org.l2start.bots.api;

/** Result of an action, as checked by the validator before anything reaches the stock AI. */
public enum ActionResult {
    OK, INVALID, COOLDOWN, NOT_REACHABLE;

    public boolean ok() {
        return this == OK;
    }
}
