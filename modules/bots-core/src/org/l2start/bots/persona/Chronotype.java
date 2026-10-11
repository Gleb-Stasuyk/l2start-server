package org.l2start.bots.persona;

/** When a person usually plays (used by the session planner in step 3e). */
public enum Chronotype {
    /** weekdays 18-01 */
    EVENING,
    /** 21-04 */
    NIGHT_OWL,
    /** mostly Saturday and Sunday, in the daytime */
    WEEKEND,
    /** student or unemployed: any time */
    ALLDAY,
    /** before work, 06-09, and a little in the evening */
    MORNING
}
