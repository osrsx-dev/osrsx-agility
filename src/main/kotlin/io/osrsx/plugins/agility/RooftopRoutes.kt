package io.osrsx.plugins.agility

import io.osrsx.api.scene.Tile

/** One obstacle that satisfies a step: the object [id] to click and the [tile] it stands on. */
data class RooftopObstacle(val id: Int, val tile: Tile)

/**
 * One step of a surveyed rooftop course — EVERY obstacle that legitimately satisfies it.
 *
 * A list rather than a single obstacle because some steps genuinely offer a choice: Falador's fourth ledge
 * is either of two, and both rejoin the course at the next step. That is not a detail, it is the bug the
 * self-discovering ring could not express — it recorded whichever one the learning lap happened to take, so
 * a later lap standing better for the other never matched, `idx` desynced, and from then on every pass fell
 * through to "nearest obstacle", which is what sent the runner backwards.
 */
data class RooftopStep(val display: String, val options: List<RooftopObstacle>)

/**
 * Surveyed, ordered obstacle tables for the rooftop courses — the rooftop analogue of
 * [PyramidCourse.ROUTE], and for the same reason: an explicit order cannot desync, where a learned one can.
 *
 * ## These are RECORDED, not derived
 *
 * Each table comes from real laps walked in game with `Debug → Log obstacle interactions` on, which logs
 * every obstacle click in traversal order with its object id and tile. Nothing here is inferred from map
 * data or from the wiki. That matters: obstacle names repeat within a course (Falador has three Ledges in a
 * row), the paired "end" hotspots share those names, and clicking an end hotspot drops the player off the
 * roof — so the id and tile together are the only safe identity.
 *
 * A course with no table falls back to [CourseRunner]'s learned ring, so recording one course at a time is
 * a safe, incremental migration rather than a flag day.
 */
object RooftopRoutes {

    /**
     * Falador rooftops (level 50) — 13 obstacles, surveyed from two manually walked laps.
     *
     * The laps were identical except at step 11, which is exactly the ambiguity being modelled: one lap took
     * Ledge `14922` at (3012,3334), the other Ledge `14923` at (3014,3335), and both continued into `14924`.
     */
    val FALADOR: List<RooftopStep> = listOf(
        step("Rough wall", 14898 to Tile(3036, 3341, 0)),
        step("Tightrope", 14899 to Tile(3040, 3343, 3)),
        step("Hand holds", 14901 to Tile(3050, 3350, 3)),
        step("Gap", 14903 to Tile(3048, 3359, 3)),
        step("Gap", 14904 to Tile(3044, 3362, 3)),
        step("Tightrope", 14905 to Tile(3034, 3361, 3)),
        step("Tightrope", 14911 to Tile(3026, 3353, 3)),
        step("Gap", 14919 to Tile(3017, 3352, 3)),
        step("Ledge", 14920 to Tile(3015, 3345, 3)),
        step("Ledge", 14921 to Tile(3012, 3343, 3)),
        // The fork: either ledge is correct, and both rejoin at the next step.
        step("Ledge", 14922 to Tile(3012, 3334, 3), 14923 to Tile(3014, 3335, 3)),
        step("Ledge", 14924 to Tile(3018, 3333, 3)),
        step("Edge", 14925 to Tile(3025, 3333, 3)),
    )

    /**
     * Canifis rooftops (level 40) — 8 obstacles, surveyed from one manually walked lap.
     *
     * The object ids are deliberately NOT in ascending order (`14848` precedes `14846`, and the pole-vault's
     * `14894` sits mid-course): the cache's numbering does not follow the route, which is the reason these
     * tables are recorded from play rather than derived.
     *
     * No fork was observed. If a step here turns out to accept a second obstacle, a lap taking the other one
     * will show it as a single differing step — the same way Falador's ledge pair surfaced.
     */
    val CANIFIS: List<RooftopStep> = listOf(
        step("Tall tree", 14843 to Tile(3506, 3489, 0)),
        step("Gap", 14844 to Tile(3505, 3498, 2)),
        step("Gap", 14845 to Tile(3496, 3504, 2)),
        step("Gap", 14848 to Tile(3485, 3499, 2)),
        step("Gap", 14846 to Tile(3478, 3491, 3)),
        step("Pole-vault", 14894 to Tile(3480, 3483, 2)),
        step("Gap", 14847 to Tile(3503, 3476, 3)),
        step("Gap", 14897 to Tile(3510, 3483, 2)),
    )

    /**
     * Every recorded route, keyed by course.
     *
     * [of] reads this and the tests iterate it, so adding a course is one entry and it is automatically
     * covered by the structural checks rather than relying on someone remembering to extend them.
     */
    val ALL: Map<Course, List<RooftopStep>> = mapOf(
        Course.FALADOR to FALADOR,
        Course.CANIFIS to CANIFIS,
    )

    private fun step(display: String, vararg options: Pair<Int, Tile>) =
        RooftopStep(display, options.map { RooftopObstacle(it.first, it.second) })

    /** The surveyed route for [course], or null when that course has not been recorded yet. */
    fun of(course: Course?): List<RooftopStep>? = course?.let { ALL[it] }

    /** The index of the step [tile] satisfies, or -1 — how a completed obstacle advances the step counter. */
    fun indexOf(route: List<RooftopStep>, tile: Tile): Int =
        route.indexOfFirst { s -> s.options.any { it.tile == tile } }
}
