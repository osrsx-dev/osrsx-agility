package io.osrsx.plugins.agility

import io.osrsx.api.PluginContext
import io.osrsx.api.player.Skill
import io.osrsx.api.scene.Tile

/** Which engine drives a [Course] — see [CourseRunner] and [PyramidRunner]. */
enum class CourseStyle {
    /** A self-discovering rooftop loop: obstacles are found live and learned into a coordinate ring. */
    ROOFTOP,

    /** The Agility Pyramid: a fixed, surveyed climb ending at the summit — see [PyramidCourse]. */
    PYRAMID,
}

/**
 * The catalogue of Agility courses the [AgilityPlugin] can run, and the live account checks that decide
 * which ones a player may actually use — mirroring the miner's [io.osrsx.plugins.skilling.MineSites].
 *
 * Each [Course] carries only its **anchor**: the [start] tile the bot web-walks to, the Agility [level] the
 * course requires, and whether it is [members]-only. A rooftop deliberately does NOT hardcode a per-obstacle
 * tile list — the [CourseRunner] finds the next obstacle by looking for the nearest reachable scene object
 * that exposes an Agility action (climb/cross/jump/…), so the course drives itself from the start tile
 * without a brittle coordinate script, and small map inaccuracies can't strand the bot.
 *
 * The Agility Pyramid is the exception, and says so through its [style]: it is a fixed climb whose
 * obstacles must be taken in a surveyed order, driven by [PyramidRunner] off [PyramidCourse.ROUTE].
 */
enum class Course(
    val id: String,
    val display: String,
    val level: Int,
    val members: Boolean,
    val start: Tile,
    val style: CourseStyle = CourseStyle.ROOFTOP,
) {
    // F2P rooftops.
    DRAYNOR("Draynor", "Draynor Village", 10, members = false, start = Tile(3103, 3279, 0)),
    AL_KHARID("AlKharid", "Al Kharid", 20, members = false, start = Tile(3273, 3195, 0)),
    VARROCK("Varrock", "Varrock", 30, members = false, start = Tile(3221, 3417, 0)),

    // The one non-rooftop course: a fixed climb to the summit, worth ~1k XP and 10k coins per lap.
    PYRAMID("Pyramid", "Agility Pyramid", 30, members = true, start = PyramidCourse.START, style = CourseStyle.PYRAMID),

    // Members rooftops.
    CANIFIS("Canifis", "Canifis", 40, members = true, start = Tile(3507, 3489, 0)),
    FALADOR("Falador", "Falador", 50, members = true, start = Tile(3036, 3341, 0)),
    SEERS("Seers", "Seers' Village", 60, members = true, start = Tile(2729, 3489, 0)),
    POLLNIVNEACH("Pollnivneach", "Pollnivneach", 70, members = true, start = Tile(3351, 2962, 0)),
    RELLEKKA("Rellekka", "Rellekka", 80, members = true, start = Tile(2625, 3675, 0)),
    ARDOUGNE("Ardougne", "Ardougne", 90, members = true, start = Tile(2673, 3298, 0));

    /** The dropdown label: the display name plus its level and (members) requirement, e.g. `"Falador (50, P2P)"`. */
    fun label(): String = "$display ($level${if (members) ", P2P" else ""})"
}

object Courses {

    /** The always-present top entry of the Course dropdown — resolved live to the highest course the account
     *  qualifies for by [resolveBest]. */
    const val BEST = "Auto — best for level"

    /**
     * Whether the current account may run [course] right now: members courses need a members world, and the
     * player's real Agility level must meet the requirement. Checks are *permissive when unknown* (world list
     * not loaded, or on the login screen) so nothing is wrongly hidden before game state is available.
     */
    fun eligible(course: Course, ctx: PluginContext): Boolean {
        if (course.members && isMembers(ctx) == false) return false
        val level = ctx.skills().real(Skill.AGILITY)
        if (level > 0 && level < course.level) return false
        return true
    }

    /** True/false when the current world's membership is known, null while the world list is still loading. */
    fun isMembers(ctx: PluginContext): Boolean? {
        val worlds = ctx.worlds()
        val current = worlds.current()
        return worlds.list().firstOrNull { it.id == current }?.members
    }

    /** The catalogued courses the account currently qualifies for, hardest last. */
    fun eligibleCourses(ctx: PluginContext): List<Course> =
        Course.entries.filter { eligible(it, ctx) }

    /** The live "Course" dropdown contents: [BEST], then every eligible course's [Course.label]. */
    fun optionsFor(ctx: PluginContext): List<String> =
        listOf(BEST) + eligibleCourses(ctx).map { it.label() }

    /** The course whose [Course.label] equals the stored config value, or null if none matches. */
    fun byLabel(label: String): Course? = Course.entries.firstOrNull { it.label() == label }

    /**
     * The highest-level ROOFTOP course the account currently qualifies for, or null if it qualifies for
     * none. The Agility Pyramid is excluded on purpose: it is a slower, gp-oriented course that also needs
     * desert-heat protection, so it is only ever run when the user asks for it by name.
     */
    fun resolveBest(ctx: PluginContext): Course? =
        eligibleCourses(ctx).filter { it.style == CourseStyle.ROOFTOP }.maxByOrNull { it.level }

    /**
     * Resolve the stored Course value to a concrete course: [BEST] (or an unknown/blank value) resolves via
     * [resolveBest]; any other value is matched by [Course.label]. Null when nothing qualifies.
     */
    fun courseFor(ctx: PluginContext, selection: String): Course? =
        if (selection == BEST || selection.isBlank()) resolveBest(ctx)
        else byLabel(selection) ?: resolveBest(ctx)
}
