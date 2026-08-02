package io.osrsx.plugins.agility

import io.osrsx.api.scene.Tile

/**
 * One obstacle of the Agility Pyramid: the object to click ([id] at [tile], menu [action]) and the
 * [landing] tile the completed traversal leaves you standing on.
 *
 * The landing is what makes the step *verifiable*. Unlike the rooftop courses — where [CourseRunner]
 * treats "a busy→idle cycle happened" as proof an obstacle was done — several pyramid obstacles need two
 * clicks: the first only auto-walks part of the way (confirmed live on the level-2 ledge and both upper
 * gaps) and produces a perfectly ordinary busy cycle that completes nothing. Advancing only once the
 * player actually stands on [landing] makes a short click idempotent: the runner simply clicks again.
 */
data class PyramidStep(
    val id: Int,
    val display: String,
    val action: String,
    val tile: Tile,
    val landing: Tile,
    /**
     * When set, the step is complete once this varbit reads 1, and [landing] is ignored.
     *
     * The summit's Climbing rocks need this: they award no Agility XP and drop the player into the same
     * corridor they could have simply walked into, so nothing about the position distinguishes "climbed" from
     * "walked past". What the climb actually does is set [PyramidCourse.TOP_EARNED] and hand over the
     * [TOP_ITEM]. Skip the rocks and the lap still "finishes" through the Doorway with nothing to show for
     * it — exactly the bug this field exists to make impossible.
     */
    val doneVarbit: Int? = null,
)

/**
 * The Agility Pyramid (level 30, members) — the one catalogued course that is **not** a rooftop loop, and
 * the reason it gets its own data table and its own runner ([PyramidRunner]) rather than riding on
 * [CourseRunner]'s learned coordinate ring.
 *
 * ## Why the rooftop engine cannot drive it
 *
 *  - **Stairs are obstacles.** [CourseRunner] excludes anything named `stairs`/`staircase` so that plain
 *    ladders beside a rooftop start are never taken. Every pyramid level is *entered* by a `Stairs`
 *    (`Climb-up`) — and each level's landing sits within two tiles of the next flight up, so a
 *    nearest-obstacle heuristic would climb straight to the top and skip every obstacle in between.
 *  - **Paired hotspots.** Each obstacle is two objects: a START you click and an END that marks where you
 *    arrive. Clicking an END drops you off the pyramid (verified live: `Gap` 10883 at (3368, 2831) put the
 *    player on the ground at (3370, 2830)). Only the START ids in [ROUTE] are ever safe to click.
 *  - **Neither distance nor reachability picks the next obstacle.** The forward obstacle is sometimes
 *    unreachable by the local pathfinder — its own tile is across the gap it spans — while a backward END
 *    hotspot is one step away. Both heuristics were measured wrong at different points of the course.
 *  - **Two regions, non-monotonic planes.** The final flight of the outer pyramid (region 13356, plane 3)
 *    teleports into the upper pyramid at (3041, 4695) — region 12105, plane **2**. Plane-based lap
 *    detection, which is how [CourseRunner] recognises a finished rooftop lap, cannot survive that.
 *
 * So the pyramid is driven from this fixed, surveyed table instead. It is a stable, non-random course:
 * every obstacle sits at a fixed world tile, and the route below was walked end-to-end and recorded from
 * the live game rather than derived from map data (the cache dump is missing the Plank/Doorway multilocs
 * entirely).
 *
 * ## The lap
 *
 * The summit's `Climbing rocks` hand over a **[TOP_ITEM]**, and the `Doorway` two steps later teleports the
 * player back to the pyramid base at (3364, 2830, 0). A lap is therefore *counted* from the item — never
 * from a position, since a fall also dumps you at the base — while the route index wraps when the Doorway's
 * landing is reached. Tops sell to [SIMON] for 10,000 coins each; see [TopsPolicy].
 *
 * ## Hazards this does NOT handle
 *
 * The `Stone block` / `Pyramid block` (NPCs 5787/5788) roll along the level-3 and upper walkways and can
 * knock the player off mid-walk; obstacles also have a real fail chance. Both end the same way — a fall to
 * the base — which [PyramidRunner] recovers from by restarting the lap. There is no block-timing logic.
 *
 * The pyramid also sits deep in the Kharidian Desert: heat drains hitpoints unless the account carries
 * desert-heat protection (a Circlet of water, Desert amulet 4, or waterskins). That is the operator's
 * responsibility — the plugin does not manage it.
 */
object PyramidCourse {

    /** The tile the plugin web-walks to: the ground-level `Stairs` that start the course. */
    val START: Tile = Tile(3354, 2831, 0)

    /** The item a completed lap awards, and what [TopsPolicy] disposes of. */
    const val TOP_ITEM = "Pyramid top"

    /** The buyer at the pyramid's base — 10,000 coins per [TOP_ITEM], through dialogue (he has no Trade). */
    const val SIMON = "Simon Templeton"

    /** Where [SIMON] stands, for the walk-to-sell leg. */
    val SIMON_TILE: Tile = Tile(3349, 2823, 0)

    /** The dialogue option that completes a sale (the alternative is "Keep it."). */
    const val SELL_OPTION = "Sell it."

    /**
     * `AGILITY_PYRAMID_TOP` — the game's own "this lap has earned its pyramid top" flag, set by the summit
     * Climbing rocks. It resets to 0 at the end of every lap (and on a fall), which is what makes it a safe
     * completion test for a step that is otherwise invisible.
     */
    const val TOP_EARNED = 1556

    /** The map region holding the outer pyramid, its base and [SIMON] — where a fall always lands you. */
    val BASE_REGION: Int = START.regionId

    /**
     * The full course, in order, as surveyed live. Steps 0–18 climb the outer pyramid (region 13356,
     * planes 0→3); step 18's flight teleports into the upper pyramid (region 12105) and the rest finish
     * there. The last step is the `Doorway` that returns the player to the base, so the list is cyclic:
     * completing it wraps the index back to 0.
     */
    val ROUTE: List<PyramidStep> = listOf(
        // ---- outer pyramid, region 13356 ----
        PyramidStep(10857, "Stairs", "Climb-up", Tile(3354, 2831, 0), Tile(3355, 2833, 1)),
        PyramidStep(10865, "Low wall", "Climb-over", Tile(3354, 2849, 1), Tile(3355, 2850, 1)),
        PyramidStep(10860, "Ledge", "Cross", Tile(3364, 2851, 1), Tile(3368, 2851, 1)),
        PyramidStep(10868, "Plank", "Cross", Tile(3375, 2845, 1), Tile(3375, 2840, 1)),
        PyramidStep(10863, "Gap", "Cross", Tile(3372, 2832, 1), Tile(3367, 2832, 1)),
        PyramidStep(10886, "Ledge", "Cross", Tile(3362, 2831, 1), Tile(3359, 2832, 1)),
        PyramidStep(10857, "Stairs", "Climb-up", Tile(3356, 2833, 1), Tile(3357, 2835, 2)),
        PyramidStep(10863, "Gap", "Cross", Tile(3357, 2836, 2), Tile(3357, 2841, 2)),
        PyramidStep(10859, "Gap", "Jump", Tile(3356, 2847, 2), Tile(3357, 2849, 2)),
        PyramidStep(10863, "Gap", "Cross", Tile(3359, 2849, 2), Tile(3364, 2849, 2)),
        PyramidStep(10860, "Ledge", "Cross", Tile(3372, 2839, 2), Tile(3372, 2836, 2)),
        PyramidStep(10865, "Low wall", "Climb-over", Tile(3370, 2833, 2), Tile(3369, 2834, 2)),
        PyramidStep(10859, "Gap", "Jump", Tile(3364, 2833, 2), Tile(3363, 2834, 2)),
        PyramidStep(10857, "Stairs", "Climb-up", Tile(3358, 2835, 2), Tile(3359, 2837, 3)),
        PyramidStep(10865, "Low wall", "Climb-over", Tile(3358, 2839, 3), Tile(3359, 2840, 3)),
        PyramidStep(10888, "Ledge", "Cross", Tile(3358, 2843, 3), Tile(3359, 2847, 3)),
        // Walking east to this gap passes the rolling Stone block — the one stretch that can knock us off.
        PyramidStep(10859, "Gap", "Jump", Tile(3370, 2841, 3), Tile(3370, 2840, 3)),
        PyramidStep(10868, "Plank", "Cross", Tile(3370, 2835, 3), Tile(3365, 2835, 3)),
        PyramidStep(10857, "Stairs", "Climb-up", Tile(3360, 2837, 3), Tile(3041, 4695, 2)),
        // ---- upper pyramid, region 12105 ----
        PyramidStep(10859, "Gap", "Jump", Tile(3040, 4697, 2), Tile(3041, 4699, 2)),
        PyramidStep(10865, "Low wall", "Climb-over", Tile(3042, 4701, 2), Tile(3043, 4701, 2)),
        PyramidStep(10859, "Gap", "Jump", Tile(3048, 4695, 2), Tile(3048, 4694, 2)),
        PyramidStep(10865, "Low wall", "Climb-over", Tile(3047, 4693, 2), Tile(3046, 4694, 2)),
        PyramidStep(10857, "Stairs", "Climb-up", Tile(3042, 4695, 2), Tile(3042, 4697, 3)),
        // The summit. Climbing the rocks is what hands over the TOP_ITEM and sets TOP_EARNED; skip them and
        // the lap still "finishes" through the Doorway with nothing to show for it.
        PyramidStep(10851, "Climbing rocks", "Climb", Tile(3043, 4698, 3), Tile(3046, 4699, 3), doneVarbit = TOP_EARNED),
        PyramidStep(10859, "Gap", "Jump", Tile(3046, 4697, 3), Tile(3046, 4696, 3)),
        // The way home: the doorway drops you back at the pyramid's base, ready for the next lap.
        PyramidStep(10855, "Doorway", "Enter", Tile(3045, 4696, 3), Tile(3364, 2830, 0)),
    )

    /**
     * How close to the *expected* step's [PyramidStep.landing] counts as having completed it — see
     * [landedOn], which is what actually decides, since proximity alone is not sufficient.
     */
    const val LANDING_RADIUS = 2

    /**
     * How close a landing must be to claim a player of *unknown* position in [resumeIndexAt]. Tighter than
     * [LANDING_RADIUS] because that search runs against **every** landing at once: the closest pair on the
     * course (the upper pyramid's two low-wall landings) is only two tiles apart, so a wider radius would let
     * one position match two steps and resume half a level off.
     */
    const val RESUME_RADIUS = 1

    /**
     * The wider radius tried when no landing is within [RESUME_RADIUS] — the fall case. Failing an obstacle
     * drops the player onto the level *below*, not to the ground (measured: a level-3 fall landed at
     * (3367, 2849, 2), three tiles off the level-2 walkway), and from a ledge halfway up the pyramid the
     * web-walker cannot route home — it teleports out and walks all the way back, costing minutes and a
     * teleport charge. Rejoining the course on the level we fell to is enormously cheaper.
     *
     * At this radius two landings can both be in range; the nearest wins, and a wrong pick self-corrects
     * through the step timeout rather than persisting.
     */
    const val FALLBACK_RADIUS = 6

    /**
     * Whether a player standing at [me] has **completed** step [index]: within [LANDING_RADIUS] of its
     * landing, and closer to that landing than to the one the step was started from.
     *
     * That second clause is not paranoia — it is what makes the check correct. Two consecutive obstacles can
     * put their landings only two tiles apart (the upper pyramid's low walls at steps 20 and 22 both do), so
     * a plain radius test would mark the low wall done the instant the *previous* obstacle finished, and the
     * runner would walk past it to the stairs. Requiring the player to have actually moved toward the new
     * landing keeps the tile of slack a finished traversal needs without ever skipping an obstacle.
     */
    fun landedOn(index: Int, me: Tile): Boolean {
        val here = me.distanceTo(ROUTE[index].landing)
        if (here > LANDING_RADIUS) return false
        if (here == 0) return true // exactly on it; no side to disambiguate
        val startedFrom = ROUTE[(index + ROUTE.size - 1) % ROUTE.size].landing
        // Being NEAR the landing is not the same as being PAST the obstacle. A landing sits only a tile or
        // two beyond its own obstacle, so a fall — or a click that only auto-walked part way — can leave the
        // player short of the obstacle yet still inside its landing radius. Comparing against the previous
        // landing alone cannot tell those apart (that one is far away in both cases), so the step read as
        // complete and the run advanced past the obstacle in front of it: measured at 3355,2848, two tiles
        // from the Low wall's landing and one from the Low wall itself. Require that the landing is the
        // nearer of the two.
        return here < me.distanceTo(startedFrom) && pastObstacle(index, me)
    }

    /**
     * Whether [me] is on the FAR side of step [index]'s obstacle — the side its landing is on.
     *
     * Projecting `me - obstacle` onto `landing - obstacle` answers this for every obstacle shape in the
     * route: positive means we are beyond it, zero or negative means we are still on the approach.
     *
     * Distance cannot answer it. Comparing "nearer the landing than the obstacle" is wrong on both sides:
     * the Low wall's landing (3355,2850) is diagonally adjacent to the wall itself (3354,2849), so landing
     * on 3354,2850 is one tile from each, fails the comparison, and the runner climbs back over — the
     * observed oscillation. Comparing against only the PREVIOUS landing is wrong the other way, since that
     * is far away whichever side of this obstacle we stand on.
     */
    private fun pastObstacle(index: Int, me: Tile): Boolean {
        val obstacle = ROUTE[index].tile
        val landing = ROUTE[index].landing
        val alongX = (me.x - obstacle.x) * (landing.x - obstacle.x)
        val alongY = (me.y - obstacle.y) * (landing.y - obstacle.y)
        return alongX + alongY > 0
    }

    /**
     * Where in [ROUTE] a player standing at [me] should resume — the index of the step to perform next, or
     * `null` if the position matches no landing. Used to pick the run back up after a fall, a knock-off, or
     * simply enabling the plugin part-way up the pyramid, without re-walking from the bottom.
     *
     * Matching on *landings* rather than on obstacles is what makes this unambiguous: a landing is a tile
     * the player actually stands on, whereas the obstacle nearest to a landing is usually the one just
     * completed (the Plank you stepped off is closer than the Gap you are heading for).
     */
    fun resumeIndexAt(me: Tile, radius: Int = RESUME_RADIUS): Int? {
        val distance = { i: Int -> minOf(me.distanceTo(ROUTE[i].landing), me.distanceTo(ROUTE[i].tile)) }
        val best = anchors().filter { distance(it) <= radius }.minByOrNull(distance) ?: return null
        // Which SIDE of that step we are on decides whether it still has to be performed. Matching the
        // nearest landing and always resuming at `landing + 1` silently assumed the step was finished —
        // but an obstacle's landing sits a tile or two beyond the obstacle itself, so a fall that drops
        // you just SHORT of one still lands inside its landing radius, and the run skipped the very
        // obstacle in front of it (measured: standing at 3355,2848 is 1 tile from the Low wall and 2 from
        // its landing, and resumed on the Ledge after it). Closer to the landing than to the obstacle
        // means we are genuinely across; otherwise this step is still the one to do.
        return if (pastObstacle(best, me)) (best + 1) % ROUTE.size else best
    }

    /**
     * The step indices whose [PyramidStep.landing] is a usable position anchor. A varbit-gated step is not:
     * it barely moves the player, so its "landing" sits on top of the previous one and would resolve two
     * different states to the same place. Excluding it simply resumes *at* that step instead — which is
     * free, because a varbit-gated step that is already done completes on the very next pass.
     */
    fun anchors(): List<Int> = ROUTE.indices.filter { ROUTE[it].doneVarbit == null }

    /** True when [me] is on the ground at the pyramid's base — where every fall and every finished lap ends,
     *  and the only place the run can safely restart from (or walk off to [SIMON]). */
    fun atBase(me: Tile): Boolean = me.plane == 0 && me.regionId == BASE_REGION

    /**
     * Westmost x of the base plateau: the pyramid itself starts here, past the `Climbing rocks`.
     *
     * The plateau is entered from the desert over rocks bridging 3348 -> 3352, which only the GLOBAL walker
     * can cross (they are a transport, not a walkable tile). [SIMON] and the whole approach stand west of
     * them; the ground `Stairs` that open a lap stand six tiles east — near enough to be on screen, and so
     * to have a clickbox, from the wrong side.
     */
    const val PLATEAU_X: Int = 3352

    /**
     * True when [me] is on the plateau itself rather than merely in its region.
     *
     * [atBase] is too coarse to answer this: a region is 64 tiles wide, so the rocks and BOTH banks share
     * [BASE_REGION] and `atBase` already reads true out in the desert.
     */
    fun onPlateau(me: Tile): Boolean = atBase(me) && me.x >= PLATEAU_X

    // ---- desert support: a pyramid run is bounded by water, not by Agility ----

    /** A waterskin by remaining dose — the game names them `Waterskin(0)` .. `Waterskin(4)`. */
    fun skinName(doses: Int): String = "Waterskin($doses)"

    /** A skin holding all four doses. The refill trigger counts THESE, not empty ones. */
    val FULL_SKIN: String = skinName(4)

    /**
     * Where waterskins are refilled: the fountain in Pollnivneach, ~135 tiles north of the pyramid.
     *
     * Narrower than the map suggests, and all three candidates were tried in game before settling here:
     * the `Well` beside it (id 6249) carries no actions at all, and the desert `Water` (id 6605) offers
     * only `Collect`, which is not a refill. The fountain has no menu action either — a refill is an
     * item-on-object *use* of a skin, and one use tops up EVERY non-full skin in the inventory, so the
     * trip is a single interaction however many skins are carried.
     */
    val WATER_TILE: Tile = Tile(3367, 2970, 0)

    /** The refill object, found by NAME: `GameObjects.closest`'s id overload is a `vararg Int`, which the
     *  name overload avoids needing. */
    const val WATER_OBJECT: String = "Fountain"

    /**
     * The support bank: Nardah, 72 tiles from the pyramid — the closest bank to the COURSE.
     *
     * Fixed deliberately, rather than asking `GlobalWalker.nearestBank()` each time. That answers "which
     * bank is nearest to where I stand", which is the wrong question for a trip that has to come back: it
     * will happily route through a teleport that lands across the map, because the walk home is not part
     * of what it minimises. Pinning the bank nearest the PYRAMID makes the round trip the cost being
     * optimised, which is the only cost that matters here.
     */
    val BANK_TILE: Tile = Tile(3426, 2894, 0)
}

/** What the runner does with the [PyramidCourse.TOP_ITEM]s a lap awards once the inventory fills up. */
enum class TopsPolicy(val label: String) {
    /** Walk to [PyramidCourse.SIMON] and sell the lot — 10,000 coins each. */
    SELL("Sell to Simon Templeton"),

    /** Drop them where you stand; fastest, forfeits the coins. */
    DROP("Drop them"),

    /** Leave them in the inventory and keep running laps for the XP alone. */
    KEEP("Keep them");

    companion object {
        /** The policy whose [label] is [label], defaulting to [SELL] for an unknown/blank stored value. */
        fun of(label: String): TopsPolicy = entries.firstOrNull { it.label == label } ?: SELL
    }
}
