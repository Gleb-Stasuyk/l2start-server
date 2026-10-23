package org.l2start.bots.api;

import java.util.Optional;
import java.util.Random;

/** Geodata questions (stock GeoEngine). */
public interface Geo {
    /** Can one walk straight from a to b. */
    boolean canMoveTo(Pos a, Pos b);

    boolean canSee(Pos a, Pos b);

    /** Ground height near p. */
    int height(Pos p);

    /** A random point within radius of center that can be reached in a straight line from center. */
    Optional<Pos> randomReachablePoint(Pos center, int radius, Random rnd);

    /** Stock pathfinding is on (PathFinding &gt; 0): a move order finds its way round walls by itself. */
    default boolean pathfinding() {
        return false;
    }

    /** The point is in water (a water zone): bots never pick it as a place to go; false = dry or unknown. */
    default boolean water(Pos p) {
        return false;
    }
}
