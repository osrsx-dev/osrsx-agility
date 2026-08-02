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
        description = "Which Agility course to run — only courses your Agility level (and world) qualify " +
            "for are shown. 'Auto' picks the best rooftop; the Agility Pyramid must be chosen by name.",
    ) { ctx -> Courses.optionsFor(ctx) }

    var pickupMarks by boolItem("pickupMarks", "Pick up Marks of Grace", true,
        "Grab Marks of Grace off the rooftops as they spawn", section = "Setup")

    var pyramidTops by enumItem("pyramidTops", "Pyramid tops",
        default = TopsPolicy.SELL.label,
        options = TopsPolicy.entries.map { it.label },
        description = "Agility Pyramid only — what to do with each Pyramid top a lap awards. Selling walks " +
            "to Simon Templeton at the base (10,000 coins each) after every lap that earns one.",
        section = "Setup")

    var speed by intItem("speed", "Speed", 100, 25, 400,
        "Loop speed as a percent — higher reacts faster between obstacles (100% = normal)", section = "Setup")

    // ---- Safety: obstacles have a real fail chance, and a fall costs hitpoints. ----

    var autoEat by boolItem("autoEat", "Eat when hurt", true,
        "Eat when a failed obstacle drops your health. Applies to every course, not just the pyramid.",
        section = "Safety")

    var eatAtPercent by intItem("eatAtPercent", "Eat below (% hitpoints)", 50, 1, 99,
        "Eat once hitpoints fall to this share of the maximum", section = "Safety")

    var eatUntilPercent by intItem("eatUntilPercent", "Eat until (% hitpoints)", 70, 1, 100,
        "Once eating starts, keep eating until hitpoints reach this — one bite is rarely enough on a low " +
            "level account. Set to 100 to always top right back up.", section = "Safety")

    var minFoodToStart by intItem("minFoodToStart", "Food needed to start a lap", 3, 0, 27,
        "Never begin a pyramid lap holding fewer than this. Running out part-way up is unrecoverable — the " +
            "only way down is a fall, and a fall costs the hitpoints you no longer have any way to restore.",
        section = "Safety")

    var food by itemItem("food", "Food", default = "Cake",
        description = "What to withdraw on a pyramid restock trip. Eating is not restricted to it — " +
            "anything with an Eat action is used, so part-eaten food (2/3 cake, Slice of cake) still counts.",
        section = "Safety")

    // ---- Desert: only the Agility Pyramid uses these. Heat drains a waterskin dose every 60-120s, so a
    // run there is bounded by water rather than by Agility, and it ends stranded without a top-up. ----

    var refillWater by boolItem("refillWater", "Refill waterskins", true,
        "Top the skins up at the Pollnivneach fountain before the desert heat empties them. One use " +
            "refills every skin carried, so it is a single stop.", section = "Desert")

    var refillAtFullSkins by intItem("refillAtFullSkins", "Refill when full skins drop to", 1, 0, 10,
        "Go and refill once this many skins still hold all four doses. The round trip costs about two " +
            "doses, so the default of 1 (~4 doses in hand) leaves real margin.", section = "Desert")

    var restockFood by boolItem("restockFood", "Restock food", true,
        "Bank at Nardah — the closest bank to the pyramid — when the food runs out", section = "Desert")

    var foodCount by intItem("foodCount", "Food to carry", 10, 1, 27,
        "How many to withdraw each restock", section = "Desert")

    // ---- Camera: pin the view instead of letting the humanizer keep re-framing it. ----

    var lockCamera by boolItem("lockCamera", "Lock the camera", true,
        "Tilt fully overhead, zoom fully out, and stop the background camera humanizer touching pitch or " +
            "zoom. It may still turn the yaw to follow the course.", section = "Camera")

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

    // ---- Debug ----

    var debugObstacles by boolItem("debugObstacles", "Log obstacle interactions", false,
        "SURVEY MODE. The plugin stops playing the course and only logs Agility obstacle clicks — action, " +
            "object id and tile, in the order they happen — including your own manual ones. Walk a lap by " +
            "hand to record that course's real order for a route table.",
        section = "Debug")

    var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99,
        "Stop when Agility hits this level (0 = never)", "Stopping")
    var stopAtLaps by intItem("stopAtLaps", "Stop at laps", 0, 0, 1_000_000,
        "Stop after this many completed laps (0 = never)", "Stopping")
    var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000,
        "Stop after this many minutes (0 = never)", "Stopping")
}
