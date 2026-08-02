package io.osrsx.plugins.agility

import io.osrsx.api.input.CameraAction
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
 * queries that resolve the next obstacle and nearby marks) never fires while logged out:
 *
 *   login → coordination-yield → auto-dialogue → antiban-idle
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
    holdDialogue: () -> Boolean = { false },
) {
    beforeEach {
        if (!ctx.login().isLoggedIn()) CameraFraming.reset() // re-frame on the next session
        if (ctx.login().isLoggedIn()) {
            val want = lockInput()
            if (want && !ctx.input().isLocked()) ctx.input().lock()
            else if (!want && ctx.input().isLocked()) ctx.input().unlock()
            act("manage-run") { ctx.walker().local.manageRun() }
            // Refresh every pass: the lease auto-expires, which is what stops a crashed plugin leaving the
            // camera frozen. ROTATE and REVEAL are the yaw-only abilities — REVEAL nudges with dragRotate(dx, 0)
            // when scenery hides the avatar. PITCH, ZOOM and GLANCE all move the tilt or the wheel (GLANCE
            // passes a dy), so they are the ones to silence.
            if (Config.lockCamera) ctx.cameraControl().allowOnly(setOf(CameraAction.ROTATE, CameraAction.REVEAL))
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
        CameraFraming.releaseCamera(ctx)
        ctx.cameraControl().release()
    }
    stage("login", { !ctx.login().isLoggedIn() }) {
        status("login")
        act("login") { ctx.login().login() }
        park(1500)
    }
    // Obstacle logging is a SURVEY mode, so the plugin must not also play the course — the whole point is to
    // record a lap walked by hand. Sits ahead of every other stage (and of the runners' substages, which are
    // registered after this prologue) so nothing else can act; the MenuOptionClicked hook keeps logging.
    stage("survey", { Config.debugObstacles }) {
        status("survey — logging your obstacle clicks")
        park(Rng.uniform(400, 800))
    }
    stage("yielding", { ctx.coordination().shouldYield() }) { status("yielding"); park(Rng.uniform(1200, 2000)) }
    // The auto-continue is the right default for the incidental chat a course walks into, but it must not
    // answer a dialogue a domain stage is deliberately steering: Simon Templeton's pyramid-top sale ends on
    // a "Sell it. / Keep it." choice, and a blind continue would answer it for us. [holdDialogue] lets the
    // owning runner take the conversation.
    stage("dialogue", { !holdDialogue() && ctx.dialogues().inDialogue() }) {
        status("dialogue")
        act("dialogue") { ctx.dialogues().continueAuto() }
        park(Rng.uniform(600, 1000))
    }
    // Framing is a real mouse gesture, so it gets a stage of its own rather than running inside beforeEach,
    // and it only engages while the view is actually off target — once pinned, blocking PITCH/ZOOM above keeps
    // it there and this never fires again.
    stage("framing camera", { Config.lockCamera && !CameraFraming.done() }) {
        status("framing camera")
        act("frame-camera") { CameraFraming.step(ctx) }
        park(Rng.uniform(400, 900))
    }
    stage("idle", { Config.idleChance > 0 && Rng.chance(Config.idleChance / 100.0) }) {
        status("idle")
        park(Rng.uniform(Config.idleMin.toLong(), maxOf(Config.idleMax, Config.idleMin).toLong()))
    }
}

/**
 * Pins the view overhead and fully zoomed out ONCE, then stops touching the camera.
 *
 * Modelled on the gauntlet plugin's camera keeper, which already learned the two things that matter:
 *
 *  - **The zoom floor is client/resolution dependent.** `getScale()` was measured at ~750 when fully
 *    zoomed OUT on one setup while reaching ~6000 fully in, so no fixed "widest" number is right
 *    everywhere — the 128..896 pair in the debug server clamps the *humanizer's target*, not what the
 *    wheel can actually reach. The floor is LEARNED instead: nudge out until the scale stops changing.
 *  - **Framing must be bounded and latched.** Re-testing a target that cannot be reached, and issuing a
 *    blocking camera gesture on every pass to chase it, pinned the client at ~3fps — which is exactly
 *    what the first version of this did with a hardcoded 128 floor and a 512 pitch the engine caps at 383.
 *
 * So: one pitch attempt, accepted whatever it lands on; zoom out until the wheel stops making progress;
 * and a hard attempt ceiling so [done] always latches and the stage can never spin.
 */
internal object CameraFraming {
    private var pitched = false
    private var zoomFloor = -1
    private var lastScale = -1
    private var noProgress = 0
    private var tries = 0

    /** Re-arm between sessions (called while logged out) so a fresh run re-frames. */
    fun reset() { pitched = false; zoomFloor = -1; lastScale = -1; noProgress = 0; tries = 0 }

    /** True once framing is settled — or has spent its budget. Either way, stop. */
    fun done(): Boolean = (pitched && zoomFloor >= 0) || tries >= MAX_TRIES

    /** One framing step. Pitch first, then zoom: both are mouse gestures and would queue behind each other. */
    fun step(ctx: PluginContext) {
        if (done()) return
        tries++
        if (!pitched) {
            // The relaxer lifts the engine's 383 pitch cap. It is a PERSISTENT client flag, so it is
            // cleared again in [releaseCamera] — leaving it set would silently change the player's own
            // camera limits after the plugin stops.
            runCatching { ctx.camera().relaxPitch(true) }
            runCatching { ctx.camera().rotateToPitch(io.osrsx.api.input.Camera.PITCH_MAX_RELAXED) }
            pitched = true // accept whatever the engine allowed; never re-test an unreachable target
            return
        }
        val scale = ctx.camera().scale()
        if (scale == lastScale) {
            // The wheel rolled and the scale did not move → this is the floor for this client/resolution.
            if (++noProgress >= FLOOR_CONFIRMATIONS) zoomFloor = scale
            return
        }
        lastScale = scale
        noProgress = 0
        ctx.camera().zoom(-ZOOM_NOTCHES)
    }

    /** Hand the camera back: drop the pitch relaxer and re-arm. */
    fun releaseCamera(ctx: PluginContext) {
        runCatching { ctx.camera().relaxPitch(false) }
        reset()
    }

    /** Wheel notches per step; CameraImpl emits them one at a time, so this stays gradual. */
    private const val ZOOM_NOTCHES = 5

    /** Consecutive unchanged scales before the floor is believed (one roll can land on the same notch). */
    private const val FLOOR_CONFIRMATIONS = 2

    /** Hard ceiling on framing passes. Bounds the worst case to a few seconds and guarantees [done] latches. */
    private const val MAX_TRIES = 30
}

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
