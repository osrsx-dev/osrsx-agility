package io.osrsx.plugins.agility

import io.osrsx.api.scene.GroundItem
import io.osrsx.api.PluginContext
import io.osrsx.api.scene.SceneObject
import io.osrsx.api.scene.Tile
import io.osrsx.api.platform.section
import io.osrsx.script.ScriptScope
import io.osrsx.script.StagedScript
import io.osrsx.script.stagedScript

/**
 * The engine that actually runs a rooftop course, as a guard-less staged ladder ([StagedScript]) nested under
 * the plugin's core script (which owns the login/break/idle prologue) — the agility analogue of [NormalMiner].
 *
 * ## Finding obstacles without a hardcoded script
 *
 * An obstacle is any scene object (within [OBSTACLE_RADIUS]) exposing an Agility action (`Climb`, `Cross`,
 * `Leap`, `Balance`, `Jump`, …) that is not a plain ladder/staircase — those carry `Climb-up`/`Climb-down`
 * and sit right next to some course starts. Every obstacle is keyed by its **exact tile** (always unique;
 * object ids are not) and interacted with by that tile — never "closest by name", because consecutive
 * obstacles often share a name (Varrock has two `Gap`/`Leap`) and "closest" flips back to the one behind you.
 *
 * ## Commit-until-done
 *
 * The runner commits to ONE obstacle at a time and does not re-pick until that obstacle is actually done.
 * Per commit: rotate + [io.osrsx.api.Walking.walkStep] toward it until it is on-screen (a null [clickbox]
 * means off-screen — the player routinely lands 15+ tiles from the next obstacle), click it, then wait for a
 * full busy→idle traversal cycle before recording it. A click that never produces movement (a no-op from the
 * wrong side) is cooled down after [STUCK_MS] and skipped. Without this the runner would blow through every
 * visible obstacle in place, "completing" them faster than the avatar can move.
 *
 * ## Forward order — a learned coordinate ring
 *
 *  - **Lap 1 (learning):** take the nearest not-yet-done obstacle, appending each tile to [ring] in the order
 *    actually traversed. Completing a lap (back on the ground near the start after being elevated) freezes it.
 *  - **Lap 2+ (following):** target [ring]`[idx]` specifically, advancing [idx] per obstacle, so duplicate
 *    obstacles are disambiguated by position. Only the previous tile is blacklisted, so the ring loops.
 *  - **Mid-run start:** with a frozen ring but unknown position, the follow path falls back to the nearest
 *    non-previous obstacle and re-syncs [idx] from the tile actually traversed.
 */
class CourseRunner(
    private val ctx: PluginContext,
    private val course: () -> Course?,
    private val pickupMarks: () -> Boolean,
    private val speed: () -> Int,
    private val stats: AgilityStats,
) {
    /** One obstacle resolved in the live scene: the [obj] to click, its unique [tile] key, and the [action]. */
    private data class Target(val obj: SceneObject, val tile: Tile, val action: String)

    /** One coherent read per pass, shared by every stage (see [StagedScript]). */
    private data class Snap(val me: Tile?, val busy: Boolean, val mark: GroundItem?)

    // Obstacle traversals are long animations with brief idle dips at the ends; debounce generously so a dip
    // between the walk-to and the obstacle animation still reads as "busy".
    private val gate = IdleGate(ctx, defaultDebounceMs = 1000L)

    // ---- learned course state (reset when the selected course changes) ----
    private var courseId: String? = null
    private val ring = mutableListOf<Tile>()
    private val lapVisited = LinkedHashSet<Tile>()
    private var learning = true
    private var idx = 0
    private var prev: Tile? = null
    private var elevated = false
    private var recovering = false

    // ---- commit-until-done state for the obstacle currently being performed ----
    private var committed: Target? = null
    private var interacted = false
    private var sawBusy = false
    private var clickMs = 0L
    private val cooldown = HashMap<Tile, Long>()

    // ---- stuck watchdog: no movement for too long anywhere is treated as a fall (restart the lap) ----
    private var lastPos: Tile? = null
    private var lastProgressMs = 0L

    // ---- backtrack guard: landing tiles of the last two completed obstacles (to spot back-edges) ----
    private var lastLanding: Tile? = null
    private var prevLanding: Tile? = null

    /** The pass's coherent snapshot — written by readState for the stage BODIES to share (stage
     *  predicates receive the state, bodies don't). */
    private var sensed = Snap(null, false, null)

    val staged: StagedScript<*> = stagedScript<Snap>("agility") {
        readState { sense().also { sensed = it } }
        isComplete { false } // cyclic — the plugin's outer gate (stop targets) decides
        stage("picking up mark", { !it.busy && it.mark != null && committed == null }) { park(grabMark(sensed.mark!!)) }
        stage("advancing", { true }) { stats.status = "advancing"; park(drive(sensed.me, sensed.busy)) }
    }

    private fun sense(): Snap = ctx.profiler().section("agility/sense") {
        setPace(speed())
        val c = course()
        if (c?.id != courseId) resetFor(c)

        val me = ctx.players().localPlayer()?.tile()
        val busy = gate.stillBusy()
        updateLap(me, c)
        checkStuck(me)

        val mark = if (pickupMarks() && me != null) nearestMark(me) else null
        Snap(me, busy, mark)
    }

    /** Wipe the learned ring and progress when the player picks a different course. */
    private fun resetFor(c: Course?) {
        courseId = c?.id
        ring.clear(); lapVisited.clear(); cooldown.clear()
        learning = true; idx = 0; prev = null; elevated = false; recovering = false
        committed = null; interacted = false; sawBusy = false
        lastPos = null; lastProgressMs = 0L
        lastLanding = null; prevLanding = null
    }

    /**
     * Plane-based lap / fall detection. A rooftop lap takes the player up (plane > 0) and drops them back to the
     * ground *beside the start*; failing an obstacle instead drops them to the ground *far from the start*, at any
     * point in the course. So a ground landing near the start is a completed lap, and a ground landing far from it
     * is a fall — abandon progress and route back to the start ([onFall]).
     */
    private fun updateLap(me: Tile?, c: Course?) {
        if (me == null || c == null) return
        if (me.plane > 0) { elevated = true; return }
        if (!elevated) return // already on the ground (walking to the course, recovering, …)
        elevated = false
        if (me.distanceTo(c.start) <= LAP_RADIUS) {
            idx = 0
            if (learning && ring.size >= MIN_RING) learning = false
            stats.addLap()
        } else {
            onFall()
        }
    }

    /**
     * Watchdog: if the player hasn't moved for [STUCK_RECOVER_MS] we're wedged (a junction with no doable
     * obstacle, an unexpected object, a bad landing). Treat it like a fall — restart the lap from the start —
     * so no single unhandled spot can hang the bot indefinitely.
     */
    private fun checkStuck(me: Tile?) {
        if (me == null) return
        val now = System.currentTimeMillis()
        if (me != lastPos) { lastPos = me; lastProgressMs = now; return }
        if (!recovering && lastProgressMs != 0L && now - lastProgressMs > STUCK_RECOVER_MS) {
            onFall()
            lastProgressMs = now
        }
    }

    /** A fall dropped us off the course mid-lap: forget where we were and walk back to the start to restart. */
    private fun onFall() {
        recovering = true
        idx = 0; prev = null
        committed = null; interacted = false; sawBusy = false
        lastLanding = null; prevLanding = null
        // A fall mid-learning leaves a partial, out-of-order ring — relearn it cleanly from the start.
        if (learning) { ring.clear(); lapVisited.clear() }
    }

    /** The main per-pass driver: perform the committed obstacle, or pick the next one. */
    private suspend fun ScriptScope.drive(me: Tile?, busy: Boolean): Long {
        if (me == null) return snap(300, 700)
        // A busy period AFTER we clicked is the obstacle's walk-to + traversal animation.
        if (busy) { if (interacted) sawBusy = true; stats.status = "traversing"; return snap(250, 700) }

        // After a fall, walk all the way back to the start before touching any obstacle again.
        if (recovering) {
            val start = course()?.start
            if (start != null && me.plane == 0 && me.distanceTo(start) <= LAP_RADIUS) recovering = false
            else { stats.status = "recovering from fall"; return walkToCourse() }
        }

        committed?.let { return driveCommitted(it, me) }

        val t = selectTarget(me)
        if (t == null) {
            return if (me.plane == 0) walkToCourse() else { stats.status = "waiting"; snap(400, 1000) }
        }
        committed = t; interacted = false; sawBusy = false
        return snap(40, 90) // act on the new commitment next tick
    }

    /** Drive the obstacle we're committed to: approach → click → wait for the traversal to complete. */
    private suspend fun ScriptScope.driveCommitted(c: Target, me: Tile): Long = ctx.profiler().section("agility/obstacle") {
        val now = System.currentTimeMillis()
        val obj = objAt(c.tile)
        if (obj == null) {
            // Obstacle left the scene: if we'd already clicked it, we traversed past it → it's done.
            if (interacted) completeObstacle(c, me) else committed = null
            return@section snap(150, 400)
        }

        if (interacted) {
            // We reach here only when NOT busy. A completed busy cycle means the traversal happened.
            if (sawBusy) { completeObstacle(c, me); return@section snap(150, 450) }
            // Clicked but nothing ever moved → a no-op from the wrong side; cool it down and re-pick.
            if (now - clickMs > STUCK_MS) { cooldown[c.tile] = now + COOLDOWN_MS; committed = null; return@section snap(150, 450) }
            return@section snap(250, 600) // just clicked; give it a beat to start
        }

        // Interact as soon as the obstacle is on-screen — `interact` auto-walks to the correct interaction
        // side and performs it (verified: it climbs the Rough wall from 5 tiles away). While it is off-screen,
        // rotate it into view, and local-walk toward it ONLY when it is also far — never step onto the
        // obstacle's own tile, which for a wall/beam is the no-op position where the action does nothing.
        if (obj.clickbox() != null) {
            stats.status = "traversing"
            act("obstacle") { if (!obj.leftClickIfDefault(c.action)) obj.interact(c.action) }
            interacted = true; clickMs = now; sawBusy = false
            return@section snap(300, 700)
        }
        stats.status = "approaching"
        act("rotate") { ctx.camera().rotateToObject(obj) }
        if (me.distanceTo(c.tile) > ROTATE_ONLY_DIST) act("walk-step") { ctx.walker().local.walkStep(c.tile) }
        snap(300, 800)
    }

    /**
     * Book-keep a finished obstacle. If it returned the player to where they were TWO obstacles ago it's a
     * back-edge — a section where two objects link the same pair of roofs and the bot took the parallel one
     * backward (seen on Falador). Such an edge is cooled down hard and NOT learned into the ring, so the next
     * pick is the real forward obstacle instead of ping-ponging across the junction.
     */
    private fun completeObstacle(c: Target, landing: Tile) {
        val backEdge = prevLanding?.let { landing.distanceTo(it) <= BACKTRACK_DIST } == true
        if (backEdge) cooldown[c.tile] = System.currentTimeMillis() + BACKTRACK_COOLDOWN_MS
        else recordProgress(c)
        prevLanding = lastLanding
        lastLanding = landing
        committed = null
    }

    /** Advance the learned ring / follow index and blacklist this obstacle as the previous one. */
    private fun recordProgress(t: Target) {
        if (learning) {
            if (t.tile !in lapVisited) {
                lapVisited.add(t.tile)
                if (t.tile !in ring) ring.add(t.tile)
            }
        } else {
            val at = ring.indexOf(t.tile)
            if (at >= 0) idx = (at + 1) % ring.size
        }
        prev = t.tile
    }

    private suspend fun ScriptScope.grabMark(mark: GroundItem): Long = ctx.profiler().section("agility/mark") {
        stats.status = "grabbing mark"
        if (act("take-mark") { mark.interact("Take") }) stats.addMark()
        snap(400, 900)
    }

    private suspend fun ScriptScope.walkToCourse(): Long = ctx.profiler().section("agility/walk") {
        val start = course()?.start ?: return@section snap(800, 1600)
        val me = ctx.players().localPlayer()?.tile()
        // Stop just short of the start rather than stepping onto it — the start tile is usually the first
        // obstacle's own tile, the no-op position from which the obstacle can't be performed.
        if (me != null && me.plane == start.plane && me.distanceTo(start) <= ARRIVE_RADIUS) {
            return@section snap(300, 700)
        }
        stats.status = "walking"
        act("path-to-course") { ctx.walker().global.pathTo(start) }
        // Poll the walker on a short interval (like the standalone walker's ~150ms cadence) rather than a long
        // loop delay — otherwise travel to the course is noticeably slower than a plain web-walk.
        snap(120, 320)
    }

    /** The next obstacle to do: from the ring while following, or the nearest not-yet-done while learning. */
    private fun selectTarget(me: Tile): Target? {
        val now = System.currentTimeMillis()
        val obstacles = obstaclesInScene(me).filter { (cooldown[it.tile] ?: 0L) < now }
        if (obstacles.isEmpty()) return null
        return if (learning) {
            obstacles.filter { it.tile !in lapVisited }.minByOrNull { me.distanceTo(it.tile) }
        } else {
            val want = ring.getOrNull(idx)
            obstacles.firstOrNull { it.tile == want }
                ?: obstacles.filter { it.tile != prev }.minByOrNull { me.distanceTo(it.tile) }
                ?: obstacles.minByOrNull { me.distanceTo(it.tile) }
        }
    }

    /** The Agility obstacle object at [tile] — matched by tile, action AND name, so neither a co-located plain
     *  wall/ground object nor a same-tile ladder is returned instead of the real obstacle. */
    private fun objAt(tile: Tile): SceneObject? =
        ctx.objects().query().within(OBSTACLE_RADIUS).list().firstOrNull { o ->
            o.tile() == tile && !isBlockedName(o) && agilityAction(o) != null
        }

    /** True if [o]'s name marks it as a non-obstacle (ladder/staircase/…), so its climb verb is ignored. */
    private fun isBlockedName(o: SceneObject): Boolean {
        val name = o.name()?.lowercase() ?: return true
        return BLOCKED_NAMES.any { name.contains(it) }
    }

    /**
     * The Agility action to use on [o], or null if it isn't a course obstacle. The action is split into whole
     * word tokens and matched against [AGILITY_TOKENS] — so variants like `Swing-across` (Cable), `Teeth-grip`
     * (Zip line) and `Climb-up` (Seers wall) match, while `Chop down` does NOT match `hop` (whole-token, not
     * substring — a naive `contains` treats every tree as an obstacle). Ladders share the `Climb-up`/`Climb-down`
     * verbs but are excluded by NAME (see [isBlockedName]), not by action.
     */
    private fun agilityAction(o: SceneObject): String? =
        o.actions().firstOrNull { a -> a.lowercase().split(NON_LETTER).any { it in AGILITY_TOKENS } }

    /**
     * Every Agility obstacle in scene, keyed by tile — ladders/staircases excluded by name. Deliberately NOT
     * filtered by reachability: an obstacle *is* the crossing between two areas, so the local pathfinder reports
     * its own tile as unreachable and would strand the bot at every gap.
     */
    private fun obstaclesInScene(me: Tile): List<Target> =
        ctx.objects().query().within(OBSTACLE_RADIUS).list().mapNotNull { o ->
            val tile = o.tile() ?: return@mapNotNull null
            if (isBlockedName(o)) return@mapNotNull null
            val action = agilityAction(o) ?: return@mapNotNull null
            Target(o, tile, action)
        }.sortedBy { me.distanceTo(it.tile) }

    /**
     * The nearest Mark of Grace that is actually grabbable *now*: on the player's current plane and reachable
     * by walking. Marks frequently spawn on the NEXT rooftop, across a gap — those are within range but not
     * reachable, and fixating on one blocks the course (the mark step outranks traversal). The reachability
     * filter defers such a mark until we've crossed onto its roof.
     */
    private fun nearestMark(me: Tile): GroundItem? =
        ctx.groundItems().query().within(MARK_RADIUS).list()
            .filter { it.name()?.equals(MARK_OF_GRACE, ignoreCase = true) == true }
            .filter { g -> g.tile()?.let { it.plane == me.plane && ctx.terrain().canReachToInteract(it) } == true }
            .minByOrNull { me.distanceTo(it.tile() ?: me) }

    private companion object {
        const val MARK_OF_GRACE = "Mark of grace"

        /** Splits a menu action into whole word tokens (on spaces, hyphens, punctuation). */
        val NON_LETTER: Regex = Regex("[^a-z]+")

        /**
         * Whole-token verbs that mark a menu action as a rooftop Agility obstacle (confirmed live:
         * Climb/Cross/Leap/Balance/Hurdle/Jump-off on Varrock, Swing-across + Teeth-grip on Al Kharid). Matched
         * as complete tokens, never substrings, so `Chop down` (→ chop, down) is not mistaken for `hop`.
         * `across` catches `Walk-across`/`Swing-across`; `grip` catches `Teeth-grip`.
         */
        val AGILITY_TOKENS: Set<String> = setOf(
            "climb", "cross", "swing", "grip", "leap", "jump", "balance", "hurdle", "vault", "grab",
            "squeeze", "teeter", "hop", "dive", "edge", "across", "hang",
        )

        /** Names carrying an Agility-looking action that are NOT course obstacles — ladders/stairs/trapdoors.
         *  This (the NAME, not the action) is what excludes `Climb-up`/`Climb-down` ladders, so real obstacles
         *  that share those verbs — a course descent (Draynor's `Crate`) or start (Seers' `Wall`, Climb-up) —
         *  are still taken. */
        val BLOCKED_NAMES: List<String> = listOf("ladder", "staircase", "stairs", "stile", "trapdoor")

        /**
         * Radius (tiles) to scan for obstacles / marks. Generous on purpose: consecutive rooftop obstacles can
         * sit 16–20 tiles apart and the player often lands that far from the next one. Off-screen picks are
         * walked toward, so a wide scan just lets the runner *see* the next obstacle instead of stalling.
         */
        const val OBSTACLE_RADIUS = 24
        const val MARK_RADIUS = 14

        /** How close to the start (ground) counts as completing a lap. */
        const val LAP_RADIUS = 8

        /** Stop web-walking to the course this close to the start (don't step onto the first obstacle's tile). */
        const val ARRIVE_RADIUS = 5

        /** Minimum obstacles before a learned ring is trusted enough to freeze. */
        const val MIN_RING = 4

        /** When an off-screen obstacle is at least this far, local-walk toward it; nearer than this, only
         *  rotate it into view (never step onto its own tile). */
        const val ROTATE_ONLY_DIST = 6

        /** A clicked obstacle that never produces movement within this long is a no-op; skip it. */
        const val STUCK_MS = 3500L

        /** How long a no-op obstacle stays cooled-down before it can be tried again. */
        const val COOLDOWN_MS = 5000L

        /** No movement for this long anywhere → treat as stuck and restart the lap from the start. */
        const val STUCK_RECOVER_MS = 15_000L

        /** Landing within this many tiles of the position two obstacles ago flags a back-edge (junction). */
        const val BACKTRACK_DIST = 2

        /** How long a detected back-edge obstacle stays cooled-down (long enough to finish the lap forward). */
        const val BACKTRACK_COOLDOWN_MS = 30_000L
    }
}
