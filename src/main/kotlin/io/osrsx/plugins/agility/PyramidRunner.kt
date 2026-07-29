package io.osrsx.plugins.agility

import io.osrsx.api.PluginContext
import io.osrsx.api.platform.section
import io.osrsx.api.scene.SceneObject
import io.osrsx.api.scene.Tile
import io.osrsx.script.ScriptScope
import io.osrsx.script.StagedScript
import io.osrsx.script.stagedScript

/**
 * The engine that runs the Agility Pyramid — the pyramid analogue of [CourseRunner], nested under the
 * plugin's core script as a substage. Where the rooftop runner *discovers* its course, this one follows
 * [PyramidCourse.ROUTE]: a surveyed, fixed table of obstacles (see that file for why the pyramid can't
 * ride on the rooftop engine).
 *
 * ## Position is the only source of truth
 *
 * Every decision comes from where the avatar is standing, never from what the runner believes it did:
 *
 *  - **A step is complete when the player stands on its [PyramidStep.landing]** — not when a busy→idle
 *    cycle ends. Several pyramid obstacles need two clicks (the first only auto-walks part way and still
 *    produces an ordinary busy cycle), so "click again until we're actually across" is the correct loop,
 *    and it costs nothing when one click sufficed.
 *  - **A lap is complete when a [PyramidCourse.TOP_ITEM] appears in the inventory.** The summit Doorway
 *    awards one and teleports to the base — but so does *falling*, minus the item, so the item is the only
 *    signal that separates the two.
 *  - **Anything unrecognised is a fall.** When the expected obstacle isn't in the scene, [resync] re-derives
 *    the index from the nearest landing ([PyramidCourse.resumeIndexAt]); failing that the run restarts from
 *    the base. This is also what lets the plugin be switched on part-way up the pyramid.
 *
 * ## Disposing of tops
 *
 * Laps award one [PyramidCourse.TOP_ITEM] each, so the inventory fills. When it does — and only while
 * standing at the base, which is where every lap ends — the [TopsPolicy] stage sells the lot to
 * [PyramidCourse.SIMON] (10,000 coins each, through dialogue) or drops them.
 */
class PyramidRunner(
    private val ctx: PluginContext,
    private val policy: () -> TopsPolicy,
    private val speed: () -> Int,
    private val stats: AgilityStats,
) {
    /** One coherent read per pass, shared by every stage (see [StagedScript]). */
    private data class Snap(val me: Tile?, val busy: Boolean, val clearTops: Boolean)

    // Obstacle traversals are long animations with brief idle dips at the ends; debounce generously so a
    // dip between the walk-to and the obstacle animation still reads as "busy".
    private val gate = IdleGate(ctx, defaultDebounceMs = 1000L)

    private fun clickSnap() = snap(Config.clickDelayMin, maxOf(Config.clickDelayMax, Config.clickDelayMin))
    private fun beatSnap() = snap(Config.beatMin, maxOf(Config.beatMax, Config.beatMin))

    // ---- course progress ----
    private var idx = 0
    private var stepSinceMs = 0L
    private var lastTops = -1
    private var resyncs = 0

    /** The pass's coherent snapshot — written by readState for the stage BODIES to share (stage
     *  predicates receive the state, bodies don't). */
    private var sensed = Snap(null, false, false)

    val staged: StagedScript<*> = stagedScript<Snap>("pyramid") {
        readState { sense().also { sensed = it } }
        isComplete { false } // cyclic — the plugin's outer gate (stop targets) decides
        stage("clearing tops", { it.clearTops }) { park(clearTops()) }
        stage("climbing", { true }) { park(drive(sensed.me, sensed.busy)) }
    }

    /**
     * True while a sale dialogue is this runner's to drive. The prologue's blanket auto-continue would
     * otherwise answer Simon's "Sell it. / Keep it." prompt for us — and pick either one — so the plugin
     * hands this to [agilityPrologue] to hold its dialogue guard back.
     */
    fun isSelling(): Boolean = selling

    @Volatile private var selling = false

    private fun sense(): Snap = ctx.profiler().section("pyramid/sense") {
        setPace(speed())
        val me = ctx.players().localPlayer()?.tile()
        val busy = gate.stillBusy(Config.idleDebounce.toLong())
        val tops = ctx.inventory().count(PyramidCourse.TOP_ITEM)
        countLap(tops)

        // Only ever dispose of tops at the base: that is where every lap ends, so it is the one place the
        // walk to Simon can actually be pathed. A full inventory higher up simply waits for the lap to end.
        val clear = policy() != TopsPolicy.KEEP && tops > 0 &&
            (selling || (ctx.inventory().isFull() && me != null && PyramidCourse.atBase(me)))
        Snap(me, busy, clear)
    }

    /**
     * Recognise a finished course from the inventory. Climbing the summit rocks is what actually hands over
     * the [PyramidCourse.TOP_ITEM], so a rising count is proof the whole course was run rather than
     * short-cut — a lap that skipped an obstacle reaches the top and awards nothing.
     *
     * It is only a *score*, never route state: the rocks are two steps from the end, and resetting the index
     * here would abandon the lap at the summit instead of taking the Doorway home. The index wraps where it
     * should, when the Doorway's landing at the base is reached.
     */
    private fun countLap(tops: Int) {
        if (lastTops < 0) { lastTops = tops; return }
        if (tops > lastTops) {
            stats.addLap()
            repeat(tops - lastTops) { stats.addTop() }
        }
        lastTops = tops
    }

    /** The main per-pass driver: verify the current step, perform it, or recover. */
    private suspend fun ScriptScope.drive(me: Tile?, busy: Boolean): Long {
        if (me == null) return beatSnap()
        if (busy) { stats.status = "traversing"; return beatSnap() }

        val step = PyramidCourse.ROUTE[idx]
        if (completed(idx, me)) return advance()

        val obj = obstacleFor(step)
        if (obj == null || stuckOn()) return resync(me)
        return perform(step, obj, me)
    }

    /** Whether step [index] is done: its progress varbit is set, or the player is standing on its landing. */
    private fun completed(index: Int, me: Tile): Boolean {
        val varbit = PyramidCourse.ROUTE[index].doneVarbit ?: return PyramidCourse.landedOn(index, me)
        return ctx.varps().varbit(varbit) == 1
    }

    /** Book-keep a completed step and move to the next. The final step (the summit Doorway) is booked by
     *  [countLap] from the awarded item instead, so reaching its landing only wraps the index. */
    private fun advance(): Long {
        idx = (idx + 1) % PyramidCourse.ROUTE.size
        stepSinceMs = 0L
        resyncs = 0
        return snap(40, 90) // act on the new step next pass
    }

    /**
     * Approach and click the step's obstacle. `interact` walks to the correct side itself, so the only
     * reason to move first is visibility: a null [SceneObject.clickbox] means the object is off-screen and
     * cannot be clicked at all. Rotate it into view, and local-walk toward it only while it is also far —
     * never onto the obstacle's own tile, which for a gap or a wall is the no-op position.
     */
    private suspend fun ScriptScope.perform(step: PyramidStep, obj: SceneObject, me: Tile): Long =
        ctx.profiler().section("pyramid/obstacle") {
            // The walk TO the course is over the moment we engage the course itself; a still-driving global
            // route keeps clicking ground tiles all lap (lease contention that stretches obstacle gaps).
            if (ctx.walker().global.isNavigating()) ctx.walker().global.stop()
            if (stepSinceMs == 0L) stepSinceMs = System.currentTimeMillis()

            if (obj.clickbox() != null) {
                stats.status = step.display.lowercase()
                act("obstacle") { if (!obj.leftClickIfDefault(step.action)) obj.interact(step.action) }
                return@section clickSnap()
            }
            stats.status = "approaching ${step.display.lowercase()}"
            act("rotate") { ctx.camera().rotateToObject(obj) }
            if (me.distanceTo(step.tile) > ROTATE_ONLY_DIST) act("walk-step") { ctx.walker().local.walkStep(step.tile) }
            beatSnap()
        }

    /** A step that has been worked for too long without reaching its landing — the moving Stone block, a
     *  failed obstacle that left us somewhere unexpected, or a click the game keeps refusing. */
    private fun stuckOn(): Boolean =
        stepSinceMs != 0L && System.currentTimeMillis() - stepSinceMs > STEP_TIMEOUT_MS

    /**
     * Re-derive where we are. A fall, a knock-off, or simply enabling the plugin mid-pyramid all land here.
     *
     * Preference order: rejoin the course at the nearest known landing — exactly first, then within
     * [PyramidCourse.FALLBACK_RADIUS], which is what catches a fall onto the level below. Only if the
     * position matches nothing at all do we restart the lap from the base, because from a ledge halfway up
     * the pyramid that means the web-walker teleporting out and walking the desert again. Repeated resyncs
     * that don't lead to a completed step ([MAX_RESYNCS]) fall back to the base too, so a position the route
     * can't explain can never wedge the run.
     */
    private suspend fun ScriptScope.resync(me: Tile): Long {
        val resumed = PyramidCourse.resumeIndexAt(me)
            ?: PyramidCourse.resumeIndexAt(me, PyramidCourse.FALLBACK_RADIUS)
        if (resumed != null && ++resyncs <= MAX_RESYNCS) {
            stepSinceMs = 0L
            // Standing on the right landing with the obstacle still out of scene just needs a beat.
            if (resumed == idx) return beatSnap()
            idx = resumed
            return snap(40, 90)
        }
        idx = 0
        stepSinceMs = 0L
        resyncs = 0
        stats.status = if (PyramidCourse.atBase(me)) "starting lap" else "recovering"
        return walkTo(PyramidCourse.START, ARRIVE_RADIUS)
    }

    /** Dispose of the pyramid tops the laps have piled up, per the configured [TopsPolicy]. */
    private suspend fun ScriptScope.clearTops(): Long = ctx.profiler().section("pyramid/tops") {
        if (policy() == TopsPolicy.DROP) {
            stats.status = "dropping tops"
            act("drop-top") { ctx.inventory().drop(PyramidCourse.TOP_ITEM) }
            return@section snap(300, 700)
        }
        selling = true
        if (ctx.dialogues().inDialogue()) return@section advanceSale()

        val simon = ctx.npcs().closest(PyramidCourse.SIMON)
        if (simon == null || simon.clickbox() == null) {
            stats.status = "walking to Simon"
            if (simon != null) act("rotate") { ctx.camera().rotateToEntity(simon) }
            return@section walkTo(PyramidCourse.SIMON_TILE, TALK_RADIUS)
        }
        stats.status = "selling tops"
        if (ctx.walker().global.isNavigating()) ctx.walker().global.stop()
        act("talk-simon") { simon.interact("Talk-to") }
        snap(1200, 2200)
    }

    /**
     * Drive Simon's sale dialogue. He has no Trade option: the sale is a chat that ends on a
     * "Sell it. / Keep it." choice, so this continues until the options appear and then answers them. The
     * stage re-enters until no tops remain, which covers him buying them one at a time or in bulk.
     */
    private suspend fun ScriptScope.advanceSale(): Long {
        val dialogues = ctx.dialogues()
        if (dialogues.getOptions().any { it.equals(PyramidCourse.SELL_OPTION, ignoreCase = true) }) {
            act("sell-tops") { dialogues.chooseOption(PyramidCourse.SELL_OPTION) }
            return snap(600, 1200)
        }
        act("dialogue") { dialogues.continueDialogue() }
        return snap(400, 900)
    }

    /**
     * Web-walk toward [dest], stopping [arriveRadius] tiles short. Fired once and then polled: the walker
     * is self-driving, and re-calling `pathTo` every pass displaces the incumbent route and re-runs a whole
     * global search per beat.
     */
    private suspend fun ScriptScope.walkTo(dest: Tile, arriveRadius: Int): Long =
        ctx.profiler().section("pyramid/walk") {
            val me = ctx.players().localPlayer()?.tile()
            if (me != null && me.plane == dest.plane && me.distanceTo(dest) <= arriveRadius) {
                if (ctx.walker().global.isNavigating()) ctx.walker().global.stop()
                return@section snap(300, 700)
            }
            if (!ctx.walker().global.isNavigating()) act("path-to") { ctx.walker().global.pathTo(dest) }
            // Poll on a short interval (the standalone walker's ~150ms cadence) rather than a long loop
            // delay — otherwise travel is noticeably slower than a plain web-walk.
            snap(120, 320)
        }

    /** Called by the plugin when the selected course changes / the script restarts. */
    fun reset() {
        idx = 0
        stepSinceMs = 0L
        lastTops = -1
        resyncs = 0
        selling = false
    }

    /** Clear the selling latch once the tops are gone, so the prologue's dialogue guard resumes. */
    fun endSaleIfDone() {
        if (selling && ctx.inventory().count(PyramidCourse.TOP_ITEM) == 0) selling = false
    }

    private fun obstacleFor(step: PyramidStep): SceneObject? =
        ctx.objects().query().within(OBSTACLE_RADIUS).list()
            .firstOrNull { it.id == step.id && it.tile() == step.tile }

    private companion object {
        /** Radius (tiles) to scan for the step's obstacle. Generous: the player routinely lands 15+ tiles
         *  from the next one, and an off-screen pick is walked toward rather than abandoned. */
        const val OBSTACLE_RADIUS = 25

        /** Stop web-walking to the course this close to the start — the start tile IS the first obstacle's
         *  own tile, the no-op position from which `Climb-up` does nothing. */
        const val ARRIVE_RADIUS = 4

        /** Close enough to Simon that a Talk-to will connect. */
        const val TALK_RADIUS = 4

        /** When an off-screen obstacle is at least this far, local-walk toward it; nearer than this, only
         *  rotate it into view (never step onto its own tile). */
        const val ROTATE_ONLY_DIST = 6

        /** A step worked for this long without reaching its landing is treated as a fall and re-synced. */
        const val STEP_TIMEOUT_MS = 20_000L

        /** Consecutive re-syncs allowed before the lap is abandoned and restarted from the base. */
        const val MAX_RESYNCS = 3
    }
}
