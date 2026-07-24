package io.osrsx.plugins.agility

import io.osrsx.api.platform.BreakManager
import io.osrsx.api.PluginContext
import io.osrsx.api.scene.SceneEntity
import io.osrsx.api.get
import io.osrsx.plugin.PluginLog
import io.osrsx.script.StagedScriptBuilder
import io.osrsx.util.Rng

/**
 * The shared per-pass scaffolding every agility script needs, expressed as guard stages + a before-pass
 * side effect on the core [io.osrsx.script.StagedScript] — the direct analogue of the miner's `minerPrologue`.
 *
 * Guard stages are declared first, so they outrank the domain [io.osrsx.script.stagedScript] ladder
 * (first match wins) and the [CourseRunner] substage's side-effecting sense (the object/ground-item
 * queries that resolve the next obstacle and nearby marks) never fires while logged out or on a break:
 *
 *   login → coordination-yield → break → auto-dialogue → antiban-idle
 *
 * The old "stopping" guard is the script's completion condition now — [StagedScriptBuilder.isComplete]
 * fires on a met stop target and the plugin disables itself via the script-done contract.
 *
 * The always-run upkeep — input-lock maintenance + run-energy management — is the
 * [StagedScriptBuilder.beforeEach] hook, gated on being logged in. Keeping run energy managed here means
 * every course automatically runs when energy allows, which is exactly what an agility runner wants.
 */
fun StagedScriptBuilder<Unit>.agilityPrologue(
    ctx: PluginContext,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
    status: (String) -> Unit = {},
) {
    beforeEach {
        if (ctx.login().isLoggedIn()) {
            val want = lockInput()
            if (want && !ctx.input().isLocked()) ctx.input().lock()
            else if (!want && ctx.input().isLocked()) ctx.input().unlock()
            act("manage-run") { ctx.walker().local.manageRun() }
        }
    }
    // Dialogue policy stays the prologue's: the "dialogue" guard stage below is THE handler, so the
    // driver's own incidental continue-pump is off (it would fire before the login/yield guards).
    dialoguePump { false }
    // The old "stopping" guard: a met stop target completes the script, and ScriptPlugin's script-done
    // contract stops the plugin.
    isComplete { stopReason() != null }
    onComplete {
        PluginLog("agility").i("stopping — ${stopReason()}")
        if (ctx.input().isLocked()) ctx.input().unlock()
    }
    stage("login", { !ctx.login().isLoggedIn() }) {
        status("login")
        act("login") { ctx.login().login() }
        park(1500)
    }
    stage("yielding", { ctx.coordination().shouldYield() }) { status("yielding"); park(Rng.uniform(1200, 2000)) }
    stage("break", { ctx.services().get<BreakManager>()?.onBreak() == true }) {
        status("break")
        park(Rng.uniform(2000, 5000))
    }
    stage("dialogue", { ctx.dialogues().inDialogue() }) {
        status("dialogue")
        act("dialogue") { ctx.dialogues().continueAuto() }
        park(Rng.uniform(600, 1000))
    }
    stage("idle", { Rng.chance(IDLE_CHANCE) }) { status("idle"); park(Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS)) }
}

private const val IDLE_CHANCE = 0.03
private const val IDLE_MIN_MS = 1500L
private const val IDLE_MAX_MS = 4000L

/**
 * Debounced "still traversing" gate (the agility twin of the miner's `IdleGate`): an obstacle traversal is a
 * long animation/movement, and the pose dips to idle for a beat at the ends. Reads busy while the player is
 * moving or animating, and for up to [debounceMs] after idle first appears — so only a sustained idle (traversal
 * genuinely finished) reads as free-to-act, and the runner never re-clicks an obstacle mid-cross.
 */
class IdleGate(private val ctx: PluginContext, private val defaultDebounceMs: Long = 700L) {
    private var idleSinceMs = 0L

    fun isBusy(): Boolean {
        val me = ctx.players().localPlayer() ?: return false
        return me.isMoving || me.animation != IDLE
    }

    fun stillBusy(debounceMs: Long = defaultDebounceMs): Boolean {
        if (isBusy()) { idleSinceMs = 0L; return true }
        if (idleSinceMs == 0L) idleSinceMs = System.currentTimeMillis()
        return System.currentTimeMillis() - idleSinceMs < debounceMs
    }

    private companion object { const val IDLE = -1 }
}

/** Can we stand next to [entity] and interact with it? (mirrors the miner's `canReach`.) */
fun PluginContext.canReach(entity: SceneEntity): Boolean {
    val tile = entity.tile() ?: return false
    return terrain().canReachToInteract(tile)
}
