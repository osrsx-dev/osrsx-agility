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
 * Laps award one [PyramidCourse.TOP_ITEM] each, and each is worth 10,000 coins, so the [TopsPolicy] stage
 * disposes of them as soon as one is held rather than waiting for a full inventory: it sells to
 * [PyramidCourse.SIMON] (through dialogue) or drops them. Gated on standing at the base, which is where
 * every lap ends and where Simon is.
 */
class PyramidRunner(
    private val ctx: PluginContext,
    private val policy: () -> TopsPolicy,
    private val speed: () -> Int,
    private val stats: AgilityStats,
) {
    /** What a support trip must fetch before the next lap can safely start. */
    private enum class Restock { NONE, WATER, FOOD }

    /** One coherent read per pass, shared by every stage (see [StagedScript]). */
    private data class Snap(val me: Tile?, val busy: Boolean, val clearTops: Boolean, val restock: Restock, val eat: Boolean)

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

    /** The support errand committed to at the base, held until [outstanding] says it is satisfied. */
    private var trip = Restock.NONE

    /** The pass's coherent snapshot — written by readState for the stage BODIES to share (stage
     *  predicates receive the state, bodies don't). */
    private var sensed = Snap(null, false, false, Restock.NONE, false)

    val staged: StagedScript<*> = stagedScript<Snap>("pyramid") {
        readState { sense().also { sensed = it } }
        isComplete { false } // cyclic — the plugin's outer gate (stop targets) decides
        stage("eating", { it.eat }) { park(eatFood()) }
        stage("clearing tops", { it.clearTops }) { park(clearTops()) }
        stage("refilling water", { it.restock == Restock.WATER }) { park(refillWater()) }
        stage("restocking food", { it.restock == Restock.FOOD }) { park(restockFood()) }
        stage("climbing", { true }) { park(drive(sensed.me, sensed.busy)) }
    }

    /**
     * True while a sale dialogue is this runner's to drive. The prologue's blanket auto-continue would
     * otherwise answer Simon's "Sell it. / Keep it." prompt for us — and pick either one — so the plugin
     * hands this to [agilityPrologue] to hold its dialogue guard back.
     */
    fun isSelling(): Boolean = selling

    @Volatile private var selling = false

    /** Latched once eating starts, so a top-up runs to [Config.eatUntilPercent] rather than stopping the
     *  moment we climb back over [Config.eatAtPercent] — one bite is rarely enough. */
    private var toppingUp = false

    private fun shouldEat(): Boolean {
        if (!Config.autoEat || Config.food.isBlank()) return false
        val hp = ctx.skills().hitpointsPercent()
        if (hp <= Config.eatAtPercent) toppingUp = true
        // Never let a `until` below `at` latch us on forever.
        if (hp >= maxOf(Config.eatUntilPercent, Config.eatAtPercent)) toppingUp = false
        return toppingUp && edible() != null
    }

    /**
     * Anything in the inventory that can actually be eaten, matched by the `Eat` ACTION not by name.
     *
     * Food is renamed as it is eaten — `Cake` becomes `2/3 cake` becomes `Slice of cake` — and those are
     * unrelated item names, not variants the SDK can relate: `Item.nameMatches("Cake")` is false for both
     * (verified live). Counting the configured name alone left a run standing there unable to eat the
     * two-thirds of a cake in its own inventory, while simultaneously reporting "no food" and setting off
     * a bank trip. The action is the only honest test of edibility.
     */
    private fun edible() =
        ctx.inventory().items().firstOrNull { item -> item.actions.any { it.equals("Eat", ignoreCase = true) } }

    /** How many edibles are carried — the supply the lap has to last on. Same `Eat`-action test as [edible],
     *  so part-eaten food and any alternative (Choc-ice, cake slices) all count. */
    private fun edibleCount() =
        ctx.inventory().items().count { item -> item.actions.any { it.equals("Eat", ignoreCase = true) } }

    /** Eat whatever edible we are carrying. */
    private suspend fun ScriptScope.eatFood(): Long = ctx.profiler().section("pyramid/eat") {
        stats.status = "eating"
        act("eat") { edible()?.let { ctx.inventory().interact(it, "Eat") } ?: false }
        snap(600, 1200)
    }

    private fun sense(): Snap = ctx.profiler().section("pyramid/sense") {
        setPace(speed())
        val me = ctx.players().localPlayer()?.tile()
        val busy = gate.stillBusy(Config.idleDebounce.toLong())
        val tops = ctx.inventory().count(PyramidCourse.TOP_ITEM)
        countLap(tops)

        // Dispose of a top as soon as we have one, rather than waiting for the inventory to fill: each is
        // 10,000 coins, Simon stands at the base where every lap already ends, and holding them only means
        // carrying the gold around unbanked and a slot short. Still gated on being AT the base — that is the
        // one place the walk to Simon can be pathed from.
        val clear = policy() != TopsPolicy.KEEP && tops > 0 &&
            (selling || (me != null && PyramidCourse.atBase(me)))
        Snap(me, busy, clear, restockNeeded(me), shouldEat())
    }

    /**
     * What the run needs fetching before the next lap — and only ever asked at the BASE.
     *
     * Supplies are checked between laps rather than mid-climb on purpose: a lap is short, and abandoning
     * one halfway up means walking off a ledge or teleporting out of the pyramid to reach a fountain,
     * which costs far more than the doses it saves. Running dry partway up simply finishes the lap first.
     */
    private fun restockNeeded(me: Tile?): Restock {
        // Once committed, see the errand through wherever we are. The need is only ASSESSED at the base,
        // but the fountain and the bank are both far from it — re-testing `atBase` every pass dropped the
        // trip on the first step away, so the walker arrived at Nardah with no stage left to open the bank
        // and simply stood there.
        if (trip != Restock.NONE) {
            if (!outstanding(trip)) trip = Restock.NONE
            return trip
        }
        if (me == null || !PyramidCourse.atBase(me)) return Restock.NONE
        trip = when {
            Config.refillWater && triggered(Restock.WATER) -> Restock.WATER
            Config.restockFood && Config.food.isNotBlank() && triggered(Restock.FOOD) -> Restock.FOOD
            else -> Restock.NONE
        }
        return trip
    }

    /**
     * Whether [need] is worth making a trip for — the TRIGGER, only ever asked at the base.
     *
     * Deliberately not the same question as [outstanding]. A trigger is a margin ("we are getting low"),
     * an ending is completion ("there is nothing left to do"), and collapsing the two makes the errand
     * stop the instant it is no longer worth *starting* — which for water means walking away from the
     * fountain with skins still empty.
     */
    private fun triggered(need: Restock): Boolean = when (need) {
        // Refill while a full skin is still in hand rather than at zero: the trip itself costs water, so
        // leaving only once dry would strand the run in the desert on the way to the fountain.
        Restock.WATER -> ctx.inventory().count(PyramidCourse.FULL_SKIN) <= Config.refillAtFullSkins &&
            carryingUnfilled()
        // Below the START margin, not merely empty. Restocking only at zero is what allows the lap to be
        // entered on the last bite and run dry half-way up.
        Restock.FOOD -> edibleCount() < maxOf(Config.minFoodToStart, 1)
        Restock.NONE -> false
    }

    /** Whether a committed [trip] still has work left — what ENDS it. */
    private fun outstanding(need: Restock): Boolean = when (need) {
        // Fill EVERY skin we are carrying, not merely enough to clear the trigger. Ending on the trigger
        // left the run walking back to the pyramid from a fountain it was standing at, with unfilled skins
        // in the bag — the threshold had been crossed by the third skin and the errand called itself done.
        Restock.WATER -> carryingUnfilled()
        Restock.FOOD -> triggered(Restock.FOOD)
        Restock.NONE -> false
    }

    /** Any waterskin carried with a dose still to spare — doses 0..3, i.e. everything but [PyramidCourse.FULL_SKIN]. */
    private fun carryingUnfilled(): Boolean = (0..3).any { ctx.inventory().count(PyramidCourse.skinName(it)) > 0 }

    /**
     * Top the waterskins up at [PyramidCourse.WATER_TILE].
     *
     * The fountain exposes no menu action, so this is an item-on-object use of any skin that is not
     * already full — NOT specifically an empty one, which was the first thing I got wrong here.
     *
     * Re-entered until [carryingUnfilled] is false, so however many uses the fill actually takes, every
     * skin in the bag ends up full. Waits out the fill animation between uses rather than clicking on a
     * fixed beat: re-issuing the use mid-fill restarts it, which is a good way to stand at a fountain
     * forever filling the same skin.
     */
    private suspend fun ScriptScope.refillWater(): Long = ctx.profiler().section("pyramid/water") {
        val me = ctx.players().localPlayer()?.tile()
        val fountain = ctx.objects().closest(PyramidCourse.WATER_OBJECT)
        // Walk the whole way in, rather than treating a visible fountain as arrival. A clickbox appears
        // from far outside interaction range, so the use-on fired from across Pollnivneach: the skin gets
        // selected, the click on the fountain does nothing, and the stage re-enters forever holding a
        // selected item and never filling anything. Distance to the stand tile is the honest test — the
        // same one the bank trip already uses.
        if (me == null || me.plane != PyramidCourse.WATER_TILE.plane ||
            me.distanceTo(PyramidCourse.WATER_TILE) > TALK_RADIUS
        ) {
            stats.status = "walking to water"
            return@section walkTo(PyramidCourse.WATER_TILE, TALK_RADIUS)
        }
        // Standing at the fountain with it off camera — turn to it, don't walk again.
        if (fountain == null || fountain.clickbox() == null) {
            stats.status = "finding the fountain"
            if (fountain != null) act("rotate") { ctx.camera().rotateToEntity(fountain) }
            return@section beatSnap()
        }
        val skin = (0..3).firstNotNullOfOrNull { ctx.inventory().getItem(PyramidCourse.skinName(it)) }
            ?: return@section beatSnap()
        stats.status = "refilling waterskins"
        if (ctx.walker().global.isNavigating()) ctx.walker().global.stop()
        // Mid-fill: leave it alone. The skins tick over one at a time and a fresh use-on cancels the one
        // in progress, so clicking on a beat would refill the first skin over and over.
        if (sensed.busy) return@section beatSnap()
        act("refill-water") { ctx.inventory().useOn(skin, fountain) }
        snap(1200, 2200)
    }

    /** Withdraw more food at [PyramidCourse.BANK_TILE] (Nardah), then head back to the course. */
    private suspend fun ScriptScope.restockFood(): Long = ctx.profiler().section("pyramid/food") {
        if (ctx.bank().isOpen()) {
            stats.status = "withdrawing food"
            act("withdraw-food") { ctx.bank().withdraw(Config.food, Config.foodCount) }
            act("close-bank") { ctx.bank().close() }
            return@section snap(600, 1200)
        }
        val me = ctx.players().localPlayer()?.tile()
        if (me == null || me.distanceTo(PyramidCourse.BANK_TILE) > TALK_RADIUS) {
            stats.status = "walking to bank"
            return@section walkTo(PyramidCourse.BANK_TILE, TALK_RADIUS)
        }
        stats.status = "opening bank"
        if (ctx.walker().global.isNavigating()) ctx.walker().global.stop()
        act("open-bank") { ctx.bank().open() }
        snap(600, 1200)
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

        // Never START a lap under-supplied, and do not even walk to the pyramid to do it. Running out of
        // food part-way up is not something the runner can recover from: the obstacles only go forward, and
        // the sole way down is a fall — which costs hitpoints we would no longer be able to restore. The
        // restock stage ahead of this normally tops us up first; this is the backstop for when restocking is
        // switched off or cannot be satisfied, and it parks safely at the base instead of climbing.
        if (idx == 0 && edibleCount() < Config.minFoodToStart) {
            stats.status = "low on food — holding"
            return beatSnap()
        }

        // Finish the approach before engaging the first step. The ground Stairs sit six tiles east of the
        // `Climbing rocks`, so they already have a clickbox from the DESERT side — and [perform] treats a
        // clickbox as "close enough", stops the global walk and clicks. The local walker it then relies on
        // to close the distance cannot cross those rocks (they are a global transport), so the run wedges
        // there re-clicking a staircase it can never reach.
        //
        // Ground level ONLY. `idx` is 0 on a fresh start, so without the plane test this also fires when
        // the plugin is switched on part-way UP the pyramid: [onPlateau] is false on every ledge (it
        // requires plane 0), and web-walking to START from up there asks the walker to leave the pyramid —
        // it teleports out and walks the desert back, throwing away the climb. Above the ground we fall
        // through to [resync], which re-derives the index from the landing being stood on.
        if (idx == 0 && me.plane == 0 && !PyramidCourse.onPlateau(me)) {
            stats.status = "approaching pyramid"
            return walkTo(PyramidCourse.START, ARRIVE_RADIUS)
        }

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

        val me = ctx.players().localPlayer()?.tile()
        val simon = ctx.npcs().closest(PyramidCourse.SIMON)
        // A clickbox is NOT arrival. Simon stands west of the `Climbing rocks`, the lap ends east of them
        // on the plateau, and he is plainly on screen from up there — so talking the moment he has a
        // clickbox stops the global walk mid-crossing and clicks him from the wrong side of a transport
        // the LOCAL walker cannot cross. The trip then wedges: the walk is cancelled, the talk can never
        // connect, and the stage re-enters forever. Same failure the START approach already guards against
        // ([PyramidCourse.onPlateau]) — the walk has to actually get off the plateau first.
        // Ground level and west of the rocks — the only place a Talk-to can actually connect.
        val onHisSide = me != null && me.plane == PyramidCourse.SIMON_TILE.plane && !PyramidCourse.onPlateau(me)
        if (simon == null || simon.clickbox() == null || !onHisSide) {
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
        trip = Restock.NONE
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
