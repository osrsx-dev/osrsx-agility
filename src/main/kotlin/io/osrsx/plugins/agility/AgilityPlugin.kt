package io.osrsx.plugins.agility

import io.osrsx.api.platform.profile
import io.osrsx.plugin.Gfx2D
import io.osrsx.script.ScriptPlugin
import io.osrsx.script.stagedScript

/**
 * Agility runner. Pick a **course** (or "Auto — best for level"); the plugin web-walks to it and runs laps:
 * traversing every obstacle in order, collecting what the course drops, and keeping run energy on.
 *
 * The course dropdown lists only the courses your Agility level (and world membership) qualify for (see
 * [Courses]). Two engines sit behind it, chosen by [Course.style]:
 *
 *  - **Rooftops** — the obstacle sequence is not hardcoded. [CourseRunner] learns the course's coordinate
 *    ring on its first lap and follows it thereafter, picking up Marks of Grace along the way, so it also
 *    resumes correctly if enabled mid-run.
 *  - **The Agility Pyramid** — a fixed climb that no self-discovering heuristic can drive (see
 *    [PyramidCourse] for why). [PyramidRunner] follows a surveyed route to the summit, counts a lap from the
 *    Pyramid top it awards, and sells or drops the tops when the inventory fills.
 *
 * Every loop is profiled under `agility/…` / `pyramid/…` spans (zero-overhead when profiling is off), and a
 * live stats overlay shows level, XP/hr, laps and pickups.
 *
 * Built the same way as the miner/smither: a single [ScriptPlugin] whose core staged script owns the shared
 * prologue (login/idle/run) and delegates each pass to the substage matching the selected course.
 */
class AgilityPlugin : ScriptPlugin() {

    override fun settings() = Config

    private val stats by lazy { AgilityStats(ctx) }

    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, laps = { Config.stopAtLaps }, minutes = { Config.stopAfterMins })
    }

    private fun currentCourse(): Course? = Courses.courseFor(ctx, Config.course)

    private fun onPyramid(): Boolean = currentCourse()?.style == CourseStyle.PYRAMID

    private val rooftops by lazy {
        CourseRunner(
            ctx,
            course = { currentCourse() },
            pickupMarks = { Config.pickupMarks },
            speed = { Config.speed },
            stats = stats,
        )
    }

    private val pyramid by lazy {
        PyramidRunner(
            ctx,
            policy = { TopsPolicy.of(Config.pyramidTops) },
            speed = { Config.speed },
            stats = stats,
        )
    }

    /**
     * The plugin's single **core** staged script — the whole loop. It owns the shared prologue (login/yield/
     * dialogue/idle guard stages + the stop-target completion + input-lock/run upkeep via
     * [agilityPrologue]), its own start/stop lifecycle, and delegates each pass to the [CourseRunner]
     * substage. The [ScriptPlugin] base pumps it on the client tick — stage/gate predicates read live state
     * directly (no snapshot layer, no hops) and blocking actions route through `act { }` to the actuator
     * drain thread. Stage delays ([io.osrsx.script.ScriptScope.park]) pace as before.
     */
    private val core by lazy {
        stagedScript<Unit>("agility") {
            readState { pyramid.endSaleIfDone() }
            agilityPrologue(
                ctx, { Config.lockInput }, { stops.reason() },
                status = { stats.status = it },
                holdDialogue = { onPyramid() && pyramid.isSelling() },
            )
            onStart { stats.start(); pyramid.reset() }
            onStop { if (ctx.input().isLocked()) ctx.input().unlock() }
            substage("pyramid", { onPyramid() }, pyramid.staged)
            substage("rooftop", { true }, rooftops.staged)
        }
    }

    override fun script() = core.toScript()

    override fun onPanel(gfx: Gfx2D) = profile("agility/overlay") {
        val course = currentCourse()
        // The pyramid has no Marks of Grace; its per-lap pickup is the Pyramid top.
        val pickups = if (course?.style == CourseStyle.PYRAMID) "Tops" to stats.tops().toString()
        else "Marks" to stats.marks().toString()
        AgilityOverlay.render(gfx, stats, listOf(
            "Course" to (course?.display ?: "—"),
            "Laps" to stats.laps().toString(),
            pickups,
        ))
    }
}
