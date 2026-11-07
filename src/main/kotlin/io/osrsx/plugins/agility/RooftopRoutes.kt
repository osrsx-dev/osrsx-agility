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

    /** Draynor Village rooftops (level 1) — 7 obstacles, from laps run live on deenart (2026-11-07). */
    val DRAYNOR: List<RooftopStep> = listOf(
        step("Rough wall", 11404 to Tile(3103, 3279, 0)),
        step("Tightrope", 11405 to Tile(3098, 3277, 3)),
        step("Tightrope", 11406 to Tile(3092, 3276, 3)),
        step("Narrow wall", 11430 to Tile(3089, 3264, 3)),
        step("Wall", 11630 to Tile(3088, 3256, 3)),
        step("Gap", 11631 to Tile(3095, 3255, 3)),
        step("Crate", 11632 to Tile(3102, 3261, 3)),
    )

    /**
     * Al Kharid rooftops (level 20) — 8 obstacles, from laps run live on deenart (2026-11-07). The learned ring lost the
     * Cable here and froze at 7, with every lap then substituting its way past the hole.
     */
    val AL_KHARID: List<RooftopStep> = listOf(
        step("Rough wall", 11633 to Tile(3273, 3195, 0)),
        step("Tightrope", 14398 to Tile(3272, 3181, 3)),
        step("Cable", 14402 to Tile(3269, 3166, 3)),
        step("Zip line", 14403 to Tile(3303, 3163, 3)),
        step("Tropical tree", 14404 to Tile(3318, 3166, 1)),
        step("Roof top beams", 11634 to Tile(3316, 3179, 2)),
        step("Tightrope", 14409 to Tile(3313, 3186, 3)),
        step("Gap", 14399 to Tile(3300, 3193, 3)),
    )

    /** Varrock rooftops (level 30) — 9 obstacles, from laps run live on deenart (2026-11-07). */
    val VARROCK: List<RooftopStep> = listOf(
        step("Rough wall", 14412 to Tile(3221, 3414, 0)),
        step("Clothes line", 14413 to Tile(3213, 3414, 3)),
        step("Gap", 14414 to Tile(3200, 3416, 3)),
        step("Wall", 14832 to Tile(3191, 3415, 1)),
        step("Gap", 14833 to Tile(3195, 3401, 3)),
        step("Gap", 14834 to Tile(3209, 3399, 3)),
        step("Gap", 14835 to Tile(3233, 3402, 3)),
        step("Ledge", 14836 to Tile(3238, 3409, 3)),
        step("Edge", 14841 to Tile(3238, 3416, 3)),
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
        Course.DRAYNOR to DRAYNOR,
        Course.AL_KHARID to AL_KHARID,
        Course.VARROCK to VARROCK,
    )

    private fun step(display: String, vararg options: Pair<Int, Tile>) =
        RooftopStep(display, options.map { RooftopObstacle(it.first, it.second) })

    /** The surveyed route for [course], or null when that course has not been recorded yet. */
    fun of(course: Course?): List<RooftopStep>? = course?.let { ALL[it] }

    /** The index of the step [tile] satisfies, or -1 — how a completed obstacle advances the step counter. */
    fun indexOf(route: List<RooftopStep>, tile: Tile): Int =
        route.indexOfFirst { s -> s.options.any { it.tile == tile } }
}
