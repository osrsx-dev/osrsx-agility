package io.osrsx.plugins.agility

import io.osrsx.api.PluginContext
import io.osrsx.api.player.Skill
import io.osrsx.plugin.Gfx2D
import java.util.concurrent.ThreadLocalRandom

/**
 * Multiplier applied to every [snap] delay, driven by the plugin's "Speed" config (100% = 1.0; 200% = 0.5,
 * i.e. twice as fast). A single volatile knob so the whole loop's pace scales without threading a supplier
 * through every delay site. Set via [setPace].
 */
@Volatile
private var paceFactor: Double = 1.0

/** Set the loop pace from a speed percentage (25–400%): higher percent → shorter delays. */
fun setPace(speedPercent: Int) {
    paceFactor = 100.0 / speedPercent.coerceIn(25, 400)
}

/**
 * A humanized loop delay biased toward the SHORT end: most samples sit near [minMs] with a thin tail out to
 * [maxMs] (cubed uniform), so the bot reacts quickly the vast majority of the time while still varying over a
 * wide range — then scaled by [paceFactor] (the Speed config). Snappier and less robotic than a flat uniform.
 */
fun snap(minMs: Int, maxMs: Int): Long {
    val u = ThreadLocalRandom.current().nextDouble()
    return ((minMs + (maxMs - minMs) * u * u * u) * paceFactor).toLong().coerceAtLeast(15)
}

/**
 * Per-run bookkeeping for the agility runner — elapsed time, Agility XP + rates, a live status word, and
 * tallies of completed laps and Marks of Grace collected. Self-contained (no skilling-lib), like [MinerStats].
 */
class AgilityStats(private val ctx: PluginContext) {

    /** Live status word shown in the overlay (e.g. "traversing", "walking", "picking up mark"). */
    @Volatile var status: String = "starting"

    private var startXp = -1
    private var startMs = 0L
    private var laps = 0
    private var marks = 0

    /** Capture the baseline XP and clock. Call from `onStart`. */
    fun start() {
        startXp = ctx.skills().experience(Skill.AGILITY)
        startMs = System.currentTimeMillis()
        laps = 0
        marks = 0
    }

    fun addLap() { laps++ }
    fun addMark() { marks++ }
    fun laps(): Int = laps
    fun marks(): Int = marks

    fun level(): Int = ctx.skills().real(Skill.AGILITY)

    fun xpGained(): Int = if (startXp < 0) 0 else (ctx.skills().experience(Skill.AGILITY) - startXp).coerceAtLeast(0)

    fun elapsedMs(): Long = if (startMs == 0L) 0 else System.currentTimeMillis() - startMs

    /** [total] projected to an hourly rate over the elapsed run (0 until at least a second has passed). */
    fun perHour(total: Int): Int {
        val e = elapsedMs()
        return if (e >= 1000) (total.toLong() * 3_600_000L / e).toInt() else 0
    }
}

/**
 * The first met stop target, or null to keep running. Each target is off when its config supplier is 0 —
 * the agility twin of the miner's `StopTargets`.
 */
class StopTargets(
    private val stats: AgilityStats,
    private val level: () -> Int,
    private val laps: () -> Int,
    private val minutes: () -> Int,
) {
    fun reason(): String? {
        val lvl = level()
        if (lvl in 1..99 && stats.level() >= lvl) return "level $lvl reached"
        val n = laps()
        if (n > 0 && stats.laps() >= n) return "${stats.laps()} laps done"
        val mins = minutes()
        if (mins > 0 && stats.elapsedMs() >= mins * 60_000L) return "$mins min elapsed"
        return null
    }
}

/**
 * Renders the agility runner's live stats into the engine-managed, alt-drag-movable ImGui overlay. The box,
 * title, border and position persistence are handled by the engine — this only emits the rows.
 */
object AgilityOverlay {

    fun render(gui: Gfx2D, stats: AgilityStats, rows: List<Pair<String, String>> = emptyList()) {
        row(gui, "Status", stats.status)
        row(gui, "Level", stats.level().toString())
        row(gui, "XP", "${compact(stats.xpGained())} (${compact(stats.perHour(stats.xpGained()))}/hr)")
        rows.forEach { (label, value) -> row(gui, label, value) }
        row(gui, "Runtime", elapsed(stats.elapsedMs()))
    }

    private fun row(gui: Gfx2D, label: String, value: String) {
        gui.textColored(0.58f, 0.60f, 0.70f, 1f, "$label:")
        gui.sameLine()
        gui.textColored(0.92f, 0.94f, 0.98f, 1f, value)
    }

    /** 950 -> "950", 12400 -> "12.4k", 3_500_000 -> "3.5m" (trailing ".0" dropped). */
    fun compact(n: Int): String {
        if (n < 1000) return n.toString()
        val (value, suffix) = if (n < 1_000_000) n / 1000.0 to "k" else n / 1_000_000.0 to "m"
        val tenths = kotlin.math.round(value * 10).toInt()
        return if (tenths % 10 == 0) "${tenths / 10}$suffix" else "${tenths / 10}.${tenths % 10}$suffix"
    }

    /** ms -> "h:mm:ss" / "m:ss". */
    fun elapsed(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
