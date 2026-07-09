package io.osrsx.plugins.agility

import io.osrsx.config.PluginConfig

/**
 * The agility plugin's user-facing configuration.
 *
 * By convention (like the miner's `Config`) the option surface lives in its own top-level `object`, separate
 * from behaviour; [AgilityPlugin] references it as `Config` and hands it back from `config()`. The Course
 * dropdown is filled live from [Courses.optionsFor], so it only ever shows courses the account qualifies for.
 */
object Config : PluginConfig("agility") {

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

    var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99,
        "Stop when Agility hits this level (0 = never)", "Stopping")
    var stopAtLaps by intItem("stopAtLaps", "Stop at laps", 0, 0, 1_000_000,
        "Stop after this many completed laps (0 = never)", "Stopping")
    var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000,
        "Stop after this many minutes (0 = never)", "Stopping")
}
