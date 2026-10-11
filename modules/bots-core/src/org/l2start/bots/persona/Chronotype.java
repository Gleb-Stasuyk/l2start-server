package org.l2start.bots.persona;

import java.time.DayOfWeek;

/**
 * When a person usually plays (phase 4e): the director prefers personas whose hours it is now, and a persona's
 * level "on paper" grows by the hours it plays a day.
 */
public enum Chronotype {
    /** weekdays 18-01, weekends from midday */
    EVENING(3.0),
    /** 21-04 */
    NIGHT_OWL(3.5),
    /** mostly Saturday and Sunday, in the daytime */
    WEEKEND(2.4),
    /** student or unemployed: any time */
    ALLDAY(5.0),
    /** before work, 06-09, and a little in the evening */
    MORNING(2.0);

    private final double hoursPerDay;

    Chronotype(double hoursPerDay) {
        this.hoursPerDay = hoursPerDay;
    }

    /** Average hours a day such a person plays (weekly mean). */
    public double hoursPerDay() {
        return hoursPerDay;
    }

    /**
     * How likely such a person is in the game at this local time, 0..1.
     * @param hour local hour of the persona (its time zone)
     * @param day local day of the week
     */
    public double activity(int hour, DayOfWeek day) {
        final int h = Math.floorMod(hour, 24);
        final boolean weekend = (day == DayOfWeek.SATURDAY) || (day == DayOfWeek.SUNDAY);
        return switch (this) {
            case EVENING -> weekend ? (((h >= 12) || (h <= 1)) ? 0.8 : 0.05) : (((h >= 19) && (h <= 23)) ? 1 : ((h == 18) || (h == 0)) ? 0.6 : (h == 1) ? 0.3 : 0.05);
            case NIGHT_OWL -> ((h >= 22) || (h <= 3)) ? 1 : ((h == 21) || (h == 4)) ? 0.5 : 0.05;
            case WEEKEND -> weekend ? (((h >= 10) && (h <= 22)) ? 1 : 0.1) : (((h >= 19) && (h <= 22)) ? 0.2 : 0.03);
            case ALLDAY -> ((h >= 10) || (h <= 2)) ? 0.8 : 0.15;
            case MORNING -> ((h >= 6) && (h <= 9)) ? 1 : ((h >= 19) && (h <= 21)) ? 0.4 : 0.05;
        };
    }
}
