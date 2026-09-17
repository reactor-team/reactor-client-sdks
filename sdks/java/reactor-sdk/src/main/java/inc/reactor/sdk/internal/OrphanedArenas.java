package inc.reactor.sdk.internal;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Arenas that must never be closed.
 *
 * <p>{@code reactor_destroy} returns 0 when no callback is running and none will start — the only
 * answer on which the callback pointers may be released. It returns -1 when a callback is still
 * executing and could not be waited for. The handle is released either way, but on -1 the pointers
 * have to stay valid, because the library still holds them.
 *
 * <p>Closing the arena there would unbind an upcall stub that native code is about to call, which
 * is a jump into freed memory. So the arena is moved here instead and never closed again: a small
 * permanent leak, deliberately, because the alternative is a crash. Python keeps the same list for
 * the same reason and never empties it either.
 */
final class OrphanedArenas {

    private static final List<Arena> ORPHANED = new CopyOnWriteArrayList<>();

    private OrphanedArenas() {}

    /**
     * Keeps an arena alive forever.
     *
     * @param arena the arena whose stubs a callback may still be running in
     */
    static void keepForever(Arena arena) {
        ORPHANED.add(arena);
    }

    /**
     * How many arenas have been orphaned.
     *
     * <p>The endurance suite asserts this stays at zero — with {@code assertAlwaysZero} rather than
     * a baseline-relative check, because a leak already present on the first cycle would otherwise
     * become the accepted normal.
     *
     * @return the count
     */
    static int count() {
        return ORPHANED.size();
    }
}
