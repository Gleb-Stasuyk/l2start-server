package org.l2start.platform.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Everything a module registered; undone in reverse order on failure or disable. Thread-safe. */
public final class Ownership {
    public static final class Registration {
        private final String kind;
        private final String name;
        private final Runnable undo;

        Registration(String kind, String name, Runnable undo) {
            this.kind = kind;
            this.name = name;
            this.undo = undo;
        }

        public String kind() {
            return kind;
        }

        public String name() {
            return name;
        }
    }

    private final List<Registration> registrations = new ArrayList<>();

    public synchronized Registration add(String kind, String name, Runnable undo) {
        final Registration r = new Registration(kind, name, undo);
        registrations.add(r);
        return r;
    }

    /** Forget a registration without undoing it (e.g. a one-shot task that has finished). */
    public synchronized void forget(Registration r) {
        registrations.remove(r);
    }

    /** Undo everything, newest first. Errors of single undo actions are reported and do not stop the rest. */
    public int undoAll(Consumer<String> errorSink) {
        final List<Registration> copy;
        synchronized (this) {
            copy = new ArrayList<>(registrations);
            registrations.clear();
        }
        int n = 0;
        for (int i = copy.size() - 1; i >= 0; i--) {
            final Registration r = copy.get(i);
            try {
                r.undo.run();
                n++;
            } catch (RuntimeException e) {
                errorSink.accept("cannot undo " + r.kind + " " + r.name + ": " + e);
            }
        }
        return n;
    }

    public synchronized Map<String, Integer> counts() {
        final Map<String, Integer> out = new TreeMap<>();
        for (Registration r : registrations) {
            out.merge(r.kind, 1, Integer::sum);
        }
        return out;
    }

    public synchronized List<String> describe() {
        final List<String> out = new ArrayList<>();
        for (Registration r : registrations) {
            out.add(r.kind + " " + r.name);
        }
        return out;
    }

    public synchronized int size() {
        return registrations.size();
    }
}
