package io.osrsx.plugins.agility

import io.osrsx.plugin.PluginSettings

/**
 * The agility plugin's user-facing configuration.
 *
 * By convention (like the miner's `Config`) the option surface lives in its own top-level `object`, separate
 * from behaviour; [AgilityPlugin] references it as `Config` and hands it back from `config()`. The Course
 * dropdown is filled live from [Courses.optionsFor], so it only ever shows courses the account qualifies for.
 */
object Config : PluginSettings("agility") {

    var course by enumItem(
        "course", "Course",
        default = Courses.BEST,
        description = "Which rooftop course to run — only courses your Agility level (and world) qualify for are shown",
    ) { ctx -> Courses.optionsFor(ctx) }

    var pickupMarks by boolItem("pickupMarks", "Pick up Marks of Grace", true,
        "Grab Marks of Grace off the rooftops as they spawn", section = "Setup")

    var speed by intItem("speed", "Speed", 100, 25, 400,
        "Loop speed as a percent — higher reacts faster between obstacles (100% = normal)", section = "Setup")

    var lockInput by boolItem("lockInput", "Lock user input", false,
        "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")

    // ---- Pacing: every forced wait between obstacles, tunable live (values in ms unless noted). ----

    var idleDebounce by intItem("idleDebounce", "Idle debounce (ms)", 1000, 100, 3000,
        "How long the player must read continuously idle after a traversal before the next obstacle is " +
            "engaged. Lower = snappier laps; too low re-clicks obstacles when the animation dips mid-cross.",
        section = "Pacing")
    var clickDelayMin by intItem("clickDelayMin", "Post-click delay min", 300, 0, 2000,
        "Shortest pause right after clicking an obstacle before checking whether the traversal started.",
        section = "Pacing")
    var clickDelayMax by intItem("clickDelayMax", "Post-click delay max", 700, 0, 3000,
        "Longest pause right after clicking an obstacle (a humanized range with clickDelayMin).",
        section = "Pacing")
    var beatMin by intItem("beatMin", "Between-pass delay min", 250, 0, 2000,
        "Shortest pause between decision passes (waiting out a traversal, approach steps, completion bookkeeping).",
        section = "Pacing")
    var beatMax by intItem("beatMax", "Between-pass delay max", 700, 0, 3000,
        "Longest pause between decision passes (a humanized range with beatMin).",
        section = "Pacing")
    var idleChance by intItem("idleChance", "Random idle chance (%)", 3, 0, 25,
        "Chance per pass of inserting a think-pause (antiban). 0 disables the random idles entirely.",
        section = "Pacing")
    var idleMin by intItem("idleMin", "Random idle min (ms)", 1500, 200, 10_000,
        "Shortest antiban think-pause.", section = "Pacing")
    var idleMax by intItem("idleMax", "Random idle max (ms)", 4000, 500, 15_000,
        "Longest antiban think-pause.", section = "Pacing")

    var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99,
        "Stop when Agility hits this level (0 = never)", "Stopping")
    var stopAtLaps by intItem("stopAtLaps", "Stop at laps", 0, 0, 1_000_000,
        "Stop after this many completed laps (0 = never)", "Stopping")
    var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000,
        "Stop after this many minutes (0 = never)", "Stopping")
}
