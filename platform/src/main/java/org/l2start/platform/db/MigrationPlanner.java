package org.l2start.platform.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure decision what to apply. */
public final class MigrationPlanner {
    private MigrationPlanner() {
    }

    public record Plan(List<Migration> pending, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Plan plan(List<Migration> available, Map<Integer, AppliedMigration> applied) {
        final List<Migration> pending = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        for (Migration m : available) {
            final AppliedMigration a = applied.get(m.version());
            if (a == null) {
                pending.add(m);
            } else if (!a.success()) {
                errors.add("migration " + a.script() + " failed earlier (" + a.error() + "); fix the SQL and delete its row with success=0 from platform_module_migrations");
            } else if (!a.checksum().equals(m.checksum())) {
                errors.add("migration " + m.script() + " was changed after it had been applied (checksum mismatch); add a new V-file instead of editing an applied one");
            }
        }
        for (AppliedMigration a : applied.values()) {
            boolean found = false;
            for (Migration m : available) {
                if (m.version() == a.version()) {
                    found = true;
                }
            }
            if (!found) {
                warnings.add("applied migration " + a.script() + " no longer exists in the module");
            }
        }
        if (!pending.isEmpty() && !applied.isEmpty()) {
            int maxApplied = 0;
            for (AppliedMigration a : applied.values()) {
                maxApplied = Math.max(maxApplied, a.version());
            }
            for (Migration m : pending) {
                if (m.version() < maxApplied) {
                    warnings.add("migration " + m.script() + " is older than already applied V" + maxApplied + " and will be applied now");
                }
            }
        }
        return new Plan(pending, errors, warnings);
    }
}
