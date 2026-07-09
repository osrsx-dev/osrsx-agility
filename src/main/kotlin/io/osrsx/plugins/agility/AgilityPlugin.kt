package io.osrsx.plugins.agility

import io.osrsx.api.profile
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.PluginDescriptor
import io.osrsx.plugin.RoutinePlugin
import io.osrsx.plugin.ScriptGui
import io.osrsx.plugin.routine

/**
 * Rooftop Agility runner. Pick a **course** (or "Auto — best for level"); the plugin web-walks to it and runs
 * laps: traversing every obstacle in order, picking up Marks of Grace, and keeping run energy on.
 *
 * The course dropdown lists only the rooftops your Agility level (and world membership) qualify for (see
 * [Courses]). The obstacle sequence is not hardcoded — [CourseRunner] learns the course's coordinate ring on
 * its first lap and follows it thereafter, so it also resumes correctly if enabled mid-run. Every loop is
 * profiled under `agility/…` spans (zero-overhead when profiling is off), and a live stats overlay shows
 * level, XP/hr, laps and marks.
 *
 * Built the same way as the miner/smither: a single [RoutinePlugin] whose core routine owns the shared
 * prologue (login/break/idle/run) and delegates each tick to the [CourseRunner] sub-routine.
 */
@PluginDescriptor(
    name = "Agility",
    description = "Runs a rooftop Agility course: traverses obstacles, grabs Marks of Grace, manages run energy.",
    author = "osrsx",
    tags = ["skilling", "agility"],
)
class AgilityPlugin : RoutinePlugin(), HasOverlay {

    override fun config() = Config

    private val stats by lazy { AgilityStats(ctx) }

    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, laps = { Config.stopAtLaps }, minutes = { Config.stopAfterMins })
    }

    private fun currentCourse(): Course? = Courses.courseFor(ctx, Config.course)

    private val runner by lazy {
        CourseRunner(
            ctx,
            course = { currentCourse() },
            pickupMarks = { Config.pickupMarks },
            speed = { Config.speed },
            stats = stats,
        )
    }

    /**
     * The plugin's single **core** routine — the whole loop. It owns the shared prologue (login/yield/stop/
     * break/dialogue/idle guards + input-lock/run upkeep via [agilityPrologue]), its own start/stop lifecycle,
     * and delegates each tick to the [CourseRunner]. The [RoutinePlugin] base drives start/loop/stop.
     */
    private val core by lazy {
        routine(ctx.profiler(), "agility", status = { stats.status = it }) {
            agilityPrologue(ctx, { Config.lockInput }, { stops.reason() })
            onStart { stats.start() }
            onStop { if (ctx.input().isLocked()) ctx.input().unlock() }
            subroutine("run", { true }, runner.routine)
        }
    }

    override fun routine() = core

    override fun overlayTitle() = "Agility"

    override fun onOverlay(gui: ScriptGui) = profile("agility/overlay") {
        val target = currentCourse()?.display ?: "—"
        AgilityOverlay.render(gui, stats, listOf(
            "Course" to target,
            "Laps" to stats.laps().toString(),
            "Marks" to stats.marks().toString(),
        ))
    }
}
