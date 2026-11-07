package io.osrsx.plugins.agility

import io.osrsx.api.scene.GroundItem
import io.osrsx.api.PluginContext
import io.osrsx.api.player.Skill
import io.osrsx.api.scene.SceneObject
import io.osrsx.api.scene.Tile
import io.osrsx.api.platform.section
import io.osrsx.script.ScriptScope
import io.osrsx.script.StagedScript
import io.osrsx.script.stagedScript

/** Splits a menu action into whole word tokens (on spaces, hyphens, punctuation). */
private val NON_LETTER: Regex = Regex("[^a-z]+")

/**
 * Whole-token verbs that mark a menu action as a rooftop Agility obstacle (confirmed live:
 * Climb/Cross/Leap/Balance/Hurdle/Jump-off on Varrock, Swing-across + Teeth-grip on Al Kharid). Matched as
 * complete tokens, never substrings, so `Chop down` (→ chop, down) is not mistaken for `hop`. `across`
 * catches `Walk-across`/`Swing-across`; `grip` catches `Teeth-grip`.
 */
private val AGILITY_TOKENS: Set<String> = setOf(
    "climb", "cross", "swing", "grip", "leap", "jump", "balance", "hurdle", "vault", "grab",
    "squeeze", "teeter", "hop", "dive", "edge", "across", "hang",
)

/** Whether [action] reads as a rooftop Agility obstacle verb. File-level so the plugin's debug hook can
 *  apply the SAME test to a human's menu click rather than keeping a second copy of the verb list. */
internal fun isAgilityAction(action: String): Boolean =
    action.lowercase().split(NON_LETTER).any { it in AGILITY_TOKENS }

/**
 * The engine that actually runs a rooftop course, as a guard-less staged ladder ([StagedScript]) nested under
 * the plugin's core script (which owns the login/idle prologue) — the agility analogue of [NormalMiner].
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
    private val log = io.osrsx.plugin.PluginLog("agility")

    /** One obstacle resolved in the live scene: the [obj] to click, its unique [tile] key, and the [action]. */
    private data class Target(val obj: SceneObject, val tile: Tile, val action: String)

    /** One coherent read per pass, shared by every stage (see [StagedScript]). */
    private data class Snap(val me: Tile?, val busy: Boolean, val mark: GroundItem?, val eat: Boolean)

    // Obstacle traversals are long animations with brief idle dips at the ends; debounce generously so a dip
    // between the walk-to and the obstacle animation still reads as "busy".
    private val gate = IdleGate(ctx, defaultDebounceMs = 1000L)

    // Live-tunable pacing (Config → Pacing section): the post-click settle vs the general between-pass
    // beat. Ranges are humanized via [snap] (short-biased) and still scaled by the Speed percent.
    private fun clickSnap() = snap(Config.clickDelayMin, maxOf(Config.clickDelayMax, Config.clickDelayMin))
    private fun beatSnap() = snap(Config.beatMin, maxOf(Config.beatMax, Config.beatMin))

    // ---- learned course state (reset when the selected course changes) ----
    private var courseId: String? = null
    private val ring = mutableListOf<Tile>()
    private val lapVisited = LinkedHashSet<Tile>()
    private var learning = true
    private var idx = 0
    private var prev: Tile? = null

    /**
     * The obstacle BEFORE [prev] — the other half of the back-edge guard.
     *
     * Excluding only [prev] is not enough on a rooftop that doubles back on itself: when the ring's expected
     * obstacle is not in the scene yet, the nearest candidate is often the one two steps behind, so the
     * fallback in [selectTarget] picked it, ran the course backwards and then had to recover. Falador is the
     * clearest case. Always self-correcting, always wasted time.
     */
    private var prev2: Tile? = null
    private var elevated = false
    private var recovering = false

    // ---- commit-until-done state for the obstacle currently being performed ----
    private var committed: Target? = null
    private var interacted = false
    private var sawBusy = false
    private var clickMs = 0L

    /**
     * Agility XP at the moment the committed obstacle was clicked. Every rooftop obstacle pays XP, so a
     * busy→idle cycle that paid nothing was NOT a traversal — it was a missed click that landed as `Walk here`
     * (seen live on Pollnivneach while the camera was being re-framed). Counting that walk as the obstacle
     * marked the Gap done, the runner then tried the obstacles across it ("I can't reach that!"), and the stuck
     * watchdog finally handed a rooftop position to the web walker.
     */
    private var clickXp = -1

    /** Clicks on the committed obstacle that moved us but paid nothing — capped by [MAX_MISSES]. */
    private var misses = 0

    /**
     * The last obstacle paid and the next has not been clicked. Nothing can be mid-traversal then, so there are no idle dips
     * to debounce: the wait is for the character itself, not for [Config.idleDebounce] on top of it.
     */
    private var betweenObstacles = false

    // ---- lap detection: the ground landing is classified once the lap message has had a chance to arrive ----
    /** When the player last came down to the ground from the roofs, while it is not yet known to be a lap or a fall. */
    private var landedAtMs = 0L
    /** The game has said "Your … lap count is: N." since the last lap was booked. */
    @Volatile private var lapMessage = false
    private val cooldown = HashMap<Tile, Long>()

    // ---- stuck watchdog: no movement for too long anywhere is treated as a fall (restart the lap) ----
    private var lastPos: Tile? = null
    private var lastProgressMs = 0L

    // ---- backtrack guard: landing tiles of the last two completed obstacles (to spot back-edges) ----
    private var lastLanding: Tile? = null
    private var prevLanding: Tile? = null

    /** The pass's coherent snapshot — written by readState for the stage BODIES to share (stage
     *  predicates receive the state, bodies don't). */
    private var sensed = Snap(null, false, null, false)

    val staged: StagedScript<*> = stagedScript<Snap>("agility") {
        readState { sense().also { sensed = it } }
        isComplete { false } // cyclic — the plugin's outer gate (stop targets) decides
        stage("eating", { it.eat }) { park(eatFood()) }
        stage("picking up mark", { !it.busy && it.mark != null && committed == null }) { park(grabMark(sensed.mark!!)) }
        stage("advancing", { true }) { stats.status = "advancing"; park(drive(sensed.me, sensed.busy)) }
    }

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

    /** Eat whatever edible we are carrying. */
    private suspend fun ScriptScope.eatFood(): Long = ctx.profiler().section("agility/eat") {
        stats.status = "eating"
        act("eat") { edible()?.let { ctx.inventory().interact(it, "Eat") } ?: false }
        snap(600, 1200)
    }

    private fun sense(): Snap = ctx.profiler().section("agility/sense") {
        setPace(speed())
        val c = course()
        if (c?.id != courseId) resetFor(c)

        val me = ctx.players().localPlayer()?.tile()
        val busy = gate.stillBusy(if (betweenObstacles) 0L else Config.idleDebounce.toLong())
        updateLap(me, c)
        checkStuck(me)

        val mark = if (pickupMarks() && me != null) nearestMark(me) else null
        Snap(me, busy, mark, shouldEat())
    }

    /** Wipe the learned ring and progress when the player picks a different course. */
    private fun resetFor(c: Course?) {
        courseId = c?.id
        ring.clear(); lapVisited.clear(); cooldown.clear()
        learning = true; idx = 0; prev = null; prev2 = null; elevated = false; recovering = false
        committed = null; interacted = false; sawBusy = false; clickXp = -1; betweenObstacles = false
        lastPos = null; lastProgressMs = 0L
        lastLanding = null; prevLanding = null
        landedAtMs = 0L; lapMessage = false
    }

    /** Fed every game message by the plugin. The lap-count line is the only one that matters here. */
    fun onChat(message: String) {
        if (message.contains(LAP_MESSAGE, ignoreCase = true)) lapMessage = true
    }

    private fun agilityXp(): Int = ctx.skills().experience(Skill.AGILITY)

    /**
     * Plane-based lap / fall detection. A rooftop lap takes the player up (plane > 0) and drops them back to the
     * ground; failing an obstacle also drops them to the ground, at any point in the course. The two are told
     * apart by the game's own "lap count is" message, with a ground landing beside the start also counting as a
     * lap (the old rule, still right for the courses that end where they begin).
     *
     * Distance alone is NOT enough: Pollnivneach, Seers', Rellekka and Ardougne all end 30+ tiles from their
     * start, so every finished lap there read as a fall — the ring was wiped and re-learned on every lap and
     * never froze. A landing is therefore held as pending for [LAP_MSG_GRACE_MS], because the message and the
     * landing arrive on the same tick and either may be seen first.
     *
     * The message needs no timing of its own. Comparing it with the landing's time misjudged laps, because that is when this
     * read saw the landing, not when it happened: a think-pause across it aged the message out and the lap read as a fall
     * (3 laps on Draynor counted as 2, deenart 11-07).
     */
    private fun updateLap(me: Tile?, c: Course?) {
        if (me == null || c == null) return
        val now = System.currentTimeMillis()
        if (me.plane > 0) { elevated = true; landedAtMs = 0L; return }
        if (elevated) { elevated = false; landedAtMs = now }
        if (landedAtMs == 0L) return // already on the ground (walking to the course, recovering, …)
        when {
            lapMessage || me.distanceTo(c.start) <= LAP_RADIUS -> {
                landedAtMs = 0L; lapMessage = false
                completeLap(c, me)
            }
            now - landedAtMs > LAP_MSG_GRACE_MS -> {
                log.i("came down at ${me.x},${me.y} with no lap message - a fall, back to the start")
                landedAtMs = 0L; onFall()
            }
        }
    }

    private fun completeLap(c: Course, me: Tile) {
        idx = 0
        // The lap's last obstacle is usually still committed here — its landing and the lap message arrive
        // before the runner's next pass books it. Book it NOW, while the lap is still open, so it is part of
        // the ring (left out, the frozen ring ended one short and every lap substituted its way to the finish).
        committed?.let { last ->
            if (clickXp >= 0 && agilityXp() > clickXp) recordProgress(last)
        }
        if (learning && RooftopRoutes.of(c) == null) {
            // Only a ring learned from the course's own start is a whole lap. One learned from a run enabled
            // part-way round holds just the tail of the course, and following it next lap left most of the
            // obstacles unknown — so discard it and learn the next, complete lap instead.
            val fromStart = ring.firstOrNull()?.let { it.distanceTo(c.start) <= LAP_RADIUS } == true
            if (fromStart && ring.size >= MIN_RING) {
                learning = false
                // The ring IS the survey: dump it in traversal order so a per-course ordered table can be
                // built from a real run rather than hand-guessed coordinates.
                log.i("${c.id}: ring frozen (${ring.size}) ${ring.joinToString(" ") { "${it.x},${it.y},${it.plane}" }}")
            } else {
                log.i("${c.id}: partial lap (${ring.size} obstacles, not from the start) — relearning next lap")
                ring.clear()
            }
        }
        lapVisited.clear()
        prev = null; prev2 = null
        // Booked above if it paid. Booking it after the clear instead put it straight back into lapVisited, so
        // the next lap thought it was already done and stood in front of it (seen live: the Pollnivneach
        // drying line). The lap is over; drop the commitment.
        committed = null; interacted = false; sawBusy = false; clickXp = -1; betweenObstacles = false
        stats.addLap()
        // A course that ends away from its start (Pollnivneach, Seers', Rellekka, Ardougne) leaves us on the
        // ground with no course obstacle in reach — only scenery that happens to carry a climb/jump verb.
        // Walk back to the start first, exactly as after a fall, but without forgetting the ring.
        if (me.distanceTo(c.start) > LAP_RADIUS) recovering = true
    }

    /**
     * Watchdog: if the player hasn't moved for [STUCK_RECOVER_MS] we're wedged (a junction with no doable
     * obstacle, an unexpected object, a bad landing).
     *
     * On the ground that is treated like a fall — restart the lap from the start. On a ROOF it must not be:
     * the only way the web walker can leave a rooftop is a teleport, so "walk back to the start" from up there
     * spun on minigame teleports forever. Up on the roof, forget every cooldown and the obstacle we were
     * committed to and pick again — the next obstacle is always reachable from where a traversal left us.
     */
    private fun checkStuck(me: Tile?) {
        if (me == null) return
        val now = System.currentTimeMillis()
        if (me != lastPos) { lastPos = me; lastProgressMs = now; return }
        if (!recovering && lastProgressMs != 0L && now - lastProgressMs > STUCK_RECOVER_MS) {
            if (me.plane > 0) unstickOnRoof(me) else onFall()
            lastProgressMs = now
        }
    }

    private fun unstickOnRoof(me: Tile) {
        log.i("stuck on the roof at ${me.x},${me.y},${me.plane} — clearing cooldowns and re-picking " +
            "(committed=${committed?.tile?.let { "${it.x},${it.y}" } ?: "none"} idx=$idx/${ring.size} learning=$learning)")
        cooldown.clear()
        committed = null; interacted = false; sawBusy = false; clickXp = -1; betweenObstacles = false
        // While learning, the pick skips everything already visited this lap — so one obstacle booked by
        // mistake leaves nothing to pick, and clearing cooldowns alone changes nothing. Forget the lap's
        // visits except the obstacle just taken, so the nearest one ahead is eligible again.
        if (learning) lapVisited.retainAll(setOfNotNull(prev))
        // A surveyed course has nothing to forget, but its step may be wrong for where we are.
        RooftopRoutes.of(course())?.let { resync(it, obstaclesInScene(me), me) }
    }

    /** A fall dropped us off the course mid-lap: forget where we were and walk back to the start to restart. */
    private fun onFall() {
        recovering = true
        idx = 0; prev = null; prev2 = null
        committed = null; interacted = false; sawBusy = false; clickXp = -1; betweenObstacles = false
        lastLanding = null; prevLanding = null
        // A fall mid-learning leaves a partial, out-of-order ring — relearn it cleanly from the start.
        if (learning) { ring.clear(); lapVisited.clear() }
    }

    /** The main per-pass driver: perform the committed obstacle, or pick the next one. */
    private suspend fun ScriptScope.drive(me: Tile?, busy: Boolean): Long {
        if (me == null) return beatSnap()
        // XP arrives as the character lands, so it is proof the obstacle is done, with nothing to wait out. Booking it only
        // after the idle debounce and two pauses stood the character still for 3-4 s after every obstacle (Draynor, deenart
        // 11-07), and an obstacle finished before a pass saw it busy was not booked at all and read as a no-op.
        committed?.let { c ->
            // From any click on it: a click judged missed can turn out to have landed, and the XP still says so.
            if (clickXp >= 0 && agilityXp() > clickXp) {
                completeObstacle(c, me)
                betweenObstacles = true
                return beatSnap()
            }
        }
        // A busy period AFTER we clicked is the obstacle's walk-to + traversal animation. Looked at closely, only for the XP.
        if (busy) {
            // Really moving, not the debounce's tail. Counting that called a good click missed 0.7 s after it, and the Cable
            // was clicked again from the far side until the watchdog stepped in (Al Kharid, deenart 11-07).
            if (interacted && gate.isBusy()) sawBusy = true
            stats.status = "traversing"
            return if (interacted) snap(60, 120) else beatSnap()
        }

        // A fall always lands on the ground. Still being up on the roof means it was not one, and the web
        // walker cannot route off a rooftop except by teleporting — so never hand it a rooftop position.
        if (recovering && me.plane > 0) recovering = false

        // After a fall, walk all the way back to the start before touching any obstacle again.
        if (recovering) {
            val start = course()?.start
            if (start != null && me.plane == 0 && me.distanceTo(start) <= LAP_RADIUS) recovering = false
            else { stats.status = "recovering from fall"; return walkToCourse() }
        }

        committed?.let { return driveCommitted(it, me) }

        // On the ground a course begins at its start, and nothing else down here is ours. "The nearest obstacle in sight"
        // ran Draynor's course with Al Kharid selected, for standing beside it (deenart 11-07).
        val start = course()?.start
        if (start != null && me.plane == 0 && (start.plane != 0 || me.distanceTo(start) > LAP_RADIUS)) return walkToCourse()

        val t = selectTarget(me)
        if (t == null) {
            return if (me.plane == 0) walkToCourse() else { stats.status = "waiting"; beatSnap() }
        }
        committed = t; interacted = false; sawBusy = false; misses = 0; clickXp = -1
        return snap(40, 90) // act on the new commitment next tick
    }

    /** Drive the obstacle we're committed to: approach → click → wait for the traversal to complete. */
    private suspend fun ScriptScope.driveCommitted(c: Target, me: Tile): Long = ctx.profiler().section("agility/obstacle") {
        // The walk TO the course is over the moment we're engaging the course itself. A still-driving
        // global route (its dest is on plane 0, so it can never 'arrive' while we're on the rooftops)
        // keeps clicking ground tiles all lap — lease contention that stretched obstacle gaps to 5-10s
        // (seen live). stop() is a cheap cancellation; the SDK leaves navigator stops unwrapped.
        if (ctx.walker().global.isNavigating()) ctx.walker().global.stop()
        val now = System.currentTimeMillis()
        val obj = objAt(c.tile)
        if (obj == null) {
            // Gone from the query because the traversal changed plane, usually, and its XP comes a tick after: give it time,
            // or the booking is lost and the course is out of step after the zip line (deenart 11-07).
            if (!interacted || now - clickMs > PAY_WAIT_MS) committed = null
            return@section if (interacted) snap(60, 120) else beatSnap()
        }

        if (interacted) {
            // We reach here only when NOT busy, and [drive] has already booked it if it paid.
            if (sawBusy) {
                // Moved, but earned nothing: the click missed the obstacle and walked instead. Click it again.
                log.i("no xp from ${c.action} at ${c.tile.x},${c.tile.y},${c.tile.plane} — the click missed (${misses + 1}/$MAX_MISSES)")
                interacted = false; sawBusy = false
                if (++misses >= MAX_MISSES) { cooldown[c.tile] = now + COOLDOWN_MS; committed = null }
                return@section beatSnap()
            }
            // Clicked but nothing ever moved → a no-op from the wrong side; cool it down and re-pick.
            if (now - clickMs > STUCK_MS) {
                log.i("nothing came of ${c.action} at ${c.tile.x},${c.tile.y},${c.tile.plane} from ${me.x},${me.y}")
                cooldown[c.tile] = now + COOLDOWN_MS; committed = null
                return@section beatSnap()
            }
            return@section clickSnap() // just clicked; give it a beat to start
        }

        // Interact as soon as the obstacle is on-screen — `interact` auto-walks to the correct interaction
        // side and performs it (verified: it climbs the Rough wall from 5 tiles away). While it is off-screen,
        // rotate it into view, and local-walk toward it ONLY when it is also far — never step onto the
        // obstacle's own tile, which for a wall/beam is the no-op position where the action does nothing.
        if (obj.isClickable()) {
            stats.status = "traversing"
            if (clickXp < 0) clickXp = agilityXp()
            betweenObstacles = false
            // Whether it landed is an answer to use. Assuming it did stood through 3.5 s of "did it start?" for a click that
            // never happened, three times running with the Varrock gap under the HUD (deenart 11-07).
            val landed = act("obstacle") { obj.leftClickIfDefault(c.action) || obj.interact(c.action) }
            if (landed) {
                interacted = true; clickMs = now; sawBusy = false
                return@section clickSnap()
            }
            log.i("click on ${c.action} at ${c.tile.x},${c.tile.y},${c.tile.plane} did not land from ${me.x},${me.y} - framing it")
        }
        stats.status = "approaching"
        act("rotate") { ctx.camera().rotateToObject(obj) }
        if (me.distanceTo(c.tile) > ROTATE_ONLY_DIST) act("walk-step") { ctx.walker().local.walkStep(c.tile) }
        beatSnap()
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
        val route = RooftopRoutes.of(course())
        if (route != null) {
            // Advance by the step the obstacle BELONGS to, not by incrementing blindly — so either arm of a
            // fork lands on the same next step, and a stray click cannot shift the whole course by one.
            val at = RooftopRoutes.indexOf(route, t.tile)
            if (at >= 0) idx = (at + 1) % route.size
        } else if (learning) {
            if (t.tile !in lapVisited) {
                lapVisited.add(t.tile)
                if (t.tile !in ring) ring.add(t.tile)
            }
        } else {
            val at = ring.indexOf(t.tile)
            if (at >= 0) idx = (at + 1) % ring.size
        }
        prev2 = prev
        prev = t.tile
    }

    private suspend fun ScriptScope.grabMark(mark: GroundItem): Long = ctx.profiler().section("agility/mark") {
        stats.status = "grabbing mark"
        // A walk to a mark does have dips in it, and a click in one would take the mark twice.
        betweenObstacles = false
        if (act("take-mark") { mark.interact("Take") }) stats.addMark()
        beatSnap()
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
        // Fire ONCE and poll: the walker is self-driving, and re-calling pathTo every pass displaces the
        // incumbent route and re-runs a whole global search per beat.
        //
        // Pass our OWN tolerance rather than letting it default. A bare pathTo(start) inherits the walker's
        // ambient radius — a client-wide setting anyone can change — and whenever that is wider than
        // ARRIVE_RADIUS the two disagree: the walker reports `arrived` while still outside it, this stays in
        // the walk stage, and re-asking only gets the same instant `arrived` back. The run then stands still
        // on the way to the course. (Measured on the pyramid, whose runner had the identical defect.)
        if (!ctx.walker().global.isNavigating()) {
            act("path-to-course") { ctx.walker().global.pathTo(start, ARRIVE_RADIUS) }
        }
        // Poll the walker on a short interval (like the standalone walker's ~150ms cadence) rather than a long
        // loop delay — otherwise travel to the course is noticeably slower than a plain web-walk.
        snap(120, 320)
    }

    /** The next obstacle to do: from the ring while following, or the nearest not-yet-done while learning. */
    private fun selectTarget(me: Tile): Target? {
        val now = System.currentTimeMillis()
        val inScene = obstaclesInScene(me)
        if (inScene.isEmpty()) return null

        // A surveyed course follows its table and NEVER substitutes. If the step's obstacle is not in scene
        // yet, returning null makes [drive] wait a beat on the roof — which is right, because the alternative
        // (take the nearest thing instead) is precisely what walked the course backwards.
        //
        // Deliberately reads the UNFILTERED list: a cooldown means "that click did nothing, try something
        // else", which is sound for a learned ring but meaningless here — the step is the step, and there is
        // nothing else to try. Honouring it filtered away the only option, so [drive] sat out the whole
        // COOLDOWN_MS doing nothing (a ~5s dead wait on top of the 3.5s it takes to notice). Re-clicking the
        // same obstacle is the correct response; the commit/STUCK_MS cycle already paces the retry.
        RooftopRoutes.of(course())?.let { route ->
            // A step's obstacle is on the plane the last one lands on, so another plane means we are out of step: on a roof
            // with the ground-level first step still to do, it would wait for ever (deenart 11-07, climbed by a stray click).
            if (route[idx % route.size].options.none { it.tile.plane == me.plane }) resync(route, inScene, me)
            val step = route[idx % route.size]
            val here = inScene.filter { o -> step.options.any { it.tile == o.tile } }
            // Random among the options actually present: where a step genuinely offers two obstacles either
            // is correct, so varying which one is taken is both valid and less mechanical than always
            // picking the same one.
            return here.randomOrNull()
        }

        val obstacles = inScene.filter { (cooldown[it.tile] ?: 0L) < now }
        if (obstacles.isEmpty()) return null
        return if (learning) {
            obstacles.filter { it.tile !in lapVisited }.minByOrNull { me.distanceTo(it.tile) }
        } else {
            val want = ring.getOrNull(idx)
            val exact = obstacles.firstOrNull { it.tile == want }
            if (exact == null) {
                // Every wrong-obstacle click comes through here. Silent until now, which is why it read as
                // "it just sometimes goes backwards" rather than "the ring desynced at step N".
                log.i("substituting: want=${want?.let { "${it.x},${it.y}" } ?: "none"} idx=$idx/${ring.size} " +
                    "inScene=${obstacles.joinToString(",") { "${it.tile.x},${it.tile.y}" }}")
            }
            exact
                // Substituting is for a genuinely unknown position (a mid-run start), so it must not pick a
                // way BACKWARDS. Skip the last two obstacles first; only if nothing else is in scene fall
                // back to the looser filters, so the course can still recover rather than stall.
                ?: obstacles.filter { it.tile != prev && it.tile != prev2 }.minByOrNull { me.distanceTo(it.tile) }
                ?: obstacles.filter { it.tile != prev }.minByOrNull { me.distanceTo(it.tile) }
                ?: obstacles.minByOrNull { me.distanceTo(it.tile) }
        }
    }

    /**
     * Find our place in a surveyed [route] from where we stand: the nearest of its obstacles we can get to, not counting the
     * one just taken. Only for when the table cannot be right as it stands; the table decides otherwise.
     */
    private fun resync(route: List<RooftopStep>, inScene: List<Target>, me: Tile) {
        val ours = inScene.filter { it.tile.plane == me.plane && it.tile != prev && RooftopRoutes.indexOf(route, it.tile) >= 0 }
        val pick = ours.filter { ctx.terrain().canReachToInteract(it.tile) }.minByOrNull { me.distanceTo(it.tile) }
            ?: ours.minByOrNull { me.distanceTo(it.tile) }
            ?: return
        val at = RooftopRoutes.indexOf(route, pick.tile)
        log.i("out of step at ${me.x},${me.y},${me.plane} (step $idx) - carrying on from step $at, ${route[at].display}")
        idx = at
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
        o.actions().firstOrNull { isAgilityAction(it) }

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

        /** The game's per-lap line, e.g. "Your Pollnivneach Rooftop lap count is: 8." */
        const val LAP_MESSAGE = "lap count is"

        /** Missed clicks on one obstacle before it is cooled down and something else is picked. */
        const val MAX_MISSES = 3

        /** How long a ground landing waits for [LAP_MESSAGE] before it is judged a fall. */
        const val LAP_MSG_GRACE_MS = 2500L

        /** Stop web-walking to the course this close to the start (don't step onto the first obstacle's tile). */
        const val ARRIVE_RADIUS = 5

        /** Minimum obstacles before a learned ring is trusted enough to freeze. */
        const val MIN_RING = 4

        /** When an off-screen obstacle is at least this far, local-walk toward it; nearer than this, only
         *  rotate it into view (never step onto its own tile). */
        const val ROTATE_ONLY_DIST = 6

        /** A clicked obstacle that never produces movement within this long is a no-op; skip it. */
        const val STUCK_MS = 3500L

        /** How long a clicked obstacle that has left the scene may take to pay. A zip line is a long ride. */
        const val PAY_WAIT_MS = 15_000L

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
