package org.l2start.bots.core;

/** Test access to package-private pure helpers of DirectorRunner. */
public final class DirectorRunnerAccess {
    private DirectorRunnerAccess() {
    }

    public static boolean pkMayGo(boolean pk, org.l2start.bots.api.Assignment.Kind kind) {
        return DirectorRunner.pkMayGo(pk, kind);
    }
}
