package io.osrsx.plugins.agility

import io.osrsx.api.platform.BreakManager
import io.osrsx.api.PluginContext
import io.osrsx.api.scene.SceneEntity
import io.osrsx.api.get
import io.osrsx.plugin.Plugin
import io.osrsx.plugin.PluginLog
import io.osrsx.plugin.RoutineBuilder
import io.osrsx.util.Rng

/**
 * The shared per-tick scaffolding every agility routine needs, expressed as [io.osrsx.plugin.Routine] guards +
 * a before-tick side effect — the direct analogue of the miner's `minerPrologue`.
 *
 * Guards run in priority order BEFORE the routine senses, so a side-effecting `sense()` (the object/ground-item
 * queries that resolve the next obstacle and nearby marks) never fires while logged out or on a break:
 *
 *   login → coordination-yield → stop-target → break → auto-dialogue → antiban-idle
 *
 * The always-run upkeep — input-lock maintenance + run-energy management — is the [RoutineBuilder.beforeEach]
 * hook, gated on being logged in. Keeping run energy managed here means every course automatically runs when
 * energy allows, which is exactly what an agility runner wants.
 */
fun <C> RoutineBuilder<C>.agilityPrologue(
    ctx: PluginContext,
    lockInput: () -> Boolean,
    stopReason: () -> String?,
) {
    beforeEach {
        if (ctx.login().isLoggedIn()) {
            val want = lockInput()
            if (want && !ctx.input().isLocked()) ctx.input().lock()
            else if (!want && ctx.input().isLocked()) ctx.input().unlock()
            ctx.walker().local.manageRun()
        }
    }
    guard("login", { !ctx.login().isLoggedIn() }) { ctx.login().login(); 1500 }
    guard("yielding", { ctx.coordination().shouldYield() }) { Rng.uniform(1200, 2000) }
    guard("stopping", { stopReason() != null }) {
        PluginLog("agility").i("stopping — ${stopReason()}")
        if (ctx.input().isLocked()) ctx.input().unlock()
        Plugin.NO_LOOP
    }
    guard("break", { ctx.services().get<BreakManager>()?.onBreak() == true }) { Rng.uniform(2000, 5000) }
    guard("dialogue", { ctx.dialogues().inDialogue() }) { ctx.dialogues().continueAuto(); Rng.uniform(600, 1000) }
    guard("idle", { Rng.chance(IDLE_CHANCE) }) { Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS) }
}

private const val IDLE_CHANCE = 0.03
private const val IDLE_MIN_MS = 1500L
private const val IDLE_MAX_MS = 4000L

/**
 * Debounced "still traversing" gate (the agility twin of the miner's `IdleGate`): an obstacle traversal is a
 * long animation/movement, and the pose dips to idle for a beat at the ends. Reads busy while the player is
 * moving or animating, and for up to [debounceMs] after idle first appears — so only a sustained idle (traversal
 * genuinely finished) reads as free-to-act, and the routine never re-clicks an obstacle mid-cross.
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
