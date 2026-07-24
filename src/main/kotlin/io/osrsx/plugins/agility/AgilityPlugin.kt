package io.osrsx.plugins.agility

import io.osrsx.api.platform.profile
import io.osrsx.plugin.Gfx2D
import io.osrsx.script.ScriptPlugin
import io.osrsx.script.stagedScript

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
 * Built the same way as the miner/smither: a single [ScriptPlugin] whose core staged script owns the shared
 * prologue (login/break/idle/run) and delegates each pass to the [CourseRunner] substage.
 */
class AgilityPlugin : ScriptPlugin() {

    override fun settings() = Config

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
     * The plugin's single **core** staged script — the whole loop. It owns the shared prologue (login/yield/
     * break/dialogue/idle guard stages + the stop-target completion + input-lock/run upkeep via
     * [agilityPrologue]), its own start/stop lifecycle, and delegates each pass to the [CourseRunner]
     * substage. The [ScriptPlugin] base pumps it on the client tick — stage/gate predicates read live state
     * directly (no snapshot layer, no hops) and blocking actions route through `act { }` to the actuator
     * drain thread. Stage delays ([io.osrsx.script.ScriptScope.park]) pace as before.
     */
    private val core by lazy {
        stagedScript<Unit>("agility") {
            readState { }
            agilityPrologue(ctx, { Config.lockInput }, { stops.reason() }, status = { stats.status = it })
            onStart { stats.start() }
            onStop { if (ctx.input().isLocked()) ctx.input().unlock() }
            substage("run", { true }, runner.staged)
        }
    }

    override fun script() = core.toScript()

    override fun onPanel(gfx: Gfx2D) = profile("agility/overlay") {
        val target = currentCourse()?.display ?: "—"
        AgilityOverlay.render(gfx, stats, listOf(
            "Course" to target,
            "Laps" to stats.laps().toString(),
            "Marks" to stats.marks().toString(),
        ))
    }
}
