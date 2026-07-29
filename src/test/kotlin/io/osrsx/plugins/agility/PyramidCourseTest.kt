package io.osrsx.plugins.agility

import io.osrsx.api.scene.Tile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the surveyed pyramid route's invariants — the ones a hand-edited coordinate table can silently
 * break, and that would strand the bot on the pyramid rather than fail loudly.
 */
class PyramidCourseTest {

    /** Steps whose completion is decided by position rather than by a progress varbit. */
    private fun positional() = PyramidCourse.ROUTE.indices.filter { PyramidCourse.ROUTE[it].doneVarbit == null }

    @Test
    fun `route starts at the ground stairs and ends at the summit doorway`() {
        val first = PyramidCourse.ROUTE.first()
        assertEquals(PyramidCourse.START, first.tile, "the first step must be the tile we web-walk to")
        assertEquals("Climb-up", first.action)

        val last = PyramidCourse.ROUTE.last()
        assertEquals("Doorway", last.display)
        assertEquals("Enter", last.action)
        assertTrue(PyramidCourse.atBase(last.landing), "the lap must end back at the pyramid base")
    }

    @Test
    fun `every obstacle sits on the plane its approach lands on`() {
        // Step i is performed while standing on step (i-1)'s landing, so its object must share that plane —
        // an object on another plane is never returned by the scene query and the runner would stall.
        PyramidCourse.ROUTE.forEachIndexed { i, step ->
            if (i == 0) return@forEachIndexed
            val from = PyramidCourse.ROUTE[i - 1].landing
            assertEquals(from.plane, step.tile.plane, "step $i (${step.display} at ${step.tile}) is off-plane")
        }
    }

    @Test
    fun `arriving at one landing never completes the next step`() {
        // The regression that skipped the upper pyramid's low walls: consecutive landings can be two tiles
        // apart, so a plain radius test ticked the next step off before its obstacle was ever touched.
        positional().forEach { i ->
            val startedFrom = PyramidCourse.ROUTE[(i + PyramidCourse.ROUTE.size - 1) % PyramidCourse.ROUTE.size]
            assertTrue(!PyramidCourse.landedOn(i, startedFrom.landing),
                "step $i (${PyramidCourse.ROUTE[i].display}) reads as done from where it starts")
        }
    }

    @Test
    fun `a step completes on its own landing, with a tile of slack`() {
        positional().forEach { i ->
            val landing = PyramidCourse.ROUTE[i].landing
            assertTrue(PyramidCourse.landedOn(i, landing), "step $i must complete on its landing")

            // A tile of drift is tolerated as long as it isn't back toward where the step started — drifting
            // the other way is exactly the ambiguity landedOn exists to reject.
            val from = PyramidCourse.ROUTE[(i + PyramidCourse.ROUTE.size - 1) % PyramidCourse.ROUTE.size].landing
            val away = landing.translate(
                (landing.x - from.x).coerceIn(-1, 1).let { if (it == 0 && landing.y == from.y) 1 else it },
                (landing.y - from.y).coerceIn(-1, 1),
            )
            assertTrue(PyramidCourse.landedOn(i, away), "step $i must tolerate a tile of drift (at $away)")
        }
    }

    @Test
    fun `landings are far enough apart for resume to tell them apart`() {
        // resumeIndexAt matches an unknown position against EVERY anchor landing within RESUME_RADIUS; two
        // closer than that would make the resume ambiguous and could rewind or skip half a lap.
        PyramidCourse.anchors().forEach { a ->
            PyramidCourse.anchors().filter { it != a }.forEach { b ->
                val distance = PyramidCourse.ROUTE[a].landing.distanceTo(PyramidCourse.ROUTE[b].landing)
                assertTrue(distance > PyramidCourse.RESUME_RADIUS, "landings $a and $b are only $distance tiles apart")
            }
        }
    }

    @Test
    fun `no step clicks a paired end hotspot`() {
        // Every obstacle is a START/END pair; clicking an END drops the player off the pyramid (verified
        // live with Gap 10883). Only these START ids may ever appear in the route.
        val starts = setOf(10851, 10855, 10857, 10859, 10860, 10863, 10865, 10868, 10886, 10888)
        PyramidCourse.ROUTE.forEach { step ->
            assertTrue(step.id in starts, "${step.display} ${step.id} at ${step.tile} is not a known START id")
        }
    }

    @Test
    fun `resumeIndexAt returns the step after the landing it matches`() {
        val afterFirst = PyramidCourse.resumeIndexAt(PyramidCourse.ROUTE[0].landing)
        assertEquals(1, afterFirst, "standing on step 0's landing means step 1 is next")

        // A landing reached with a tile of drift still resolves.
        val drifted = PyramidCourse.ROUTE[3].landing.translate(1, -1)
        assertEquals(4, PyramidCourse.resumeIndexAt(drifted))

        // …and the completion check the runner applies to the step it is already on is the looser one.
        assertTrue(PyramidCourse.RESUME_RADIUS < PyramidCourse.LANDING_RADIUS)
    }

    @Test
    fun `finishing the last step wraps the route`() {
        assertEquals(0, PyramidCourse.resumeIndexAt(PyramidCourse.ROUTE.last().landing))
    }

    @Test
    fun `a fall onto the level below rejoins the course there`() {
        // Failing an obstacle drops you one level, not to the ground. Measured: a level-3 fall put the player
        // at (3367, 2849, 2) — three tiles off step 9's landing, so the run continues at step 10 instead of
        // web-walking home from a ledge (which costs a teleport and a desert crossing).
        val fell = Tile(3367, 2849, 2)
        assertNull(PyramidCourse.resumeIndexAt(fell), "too far for the exact match")
        assertEquals(10, PyramidCourse.resumeIndexAt(fell, PyramidCourse.FALLBACK_RADIUS))
    }

    @Test
    fun `an unknown position resolves to no step`() {
        assertNull(PyramidCourse.resumeIndexAt(Tile(3200, 3200, 0)), "Varrock is not on the pyramid")
        assertNull(PyramidCourse.resumeIndexAt(Tile(3354, 2831, 0)), "the start tile is an obstacle, not a landing")
    }

    @Test
    fun `atBase recognises the pyramid base and rejects the course above it`() {
        assertTrue(PyramidCourse.atBase(Tile(3370, 2830, 0)), "a fall lands on the ground by the pyramid")
        assertTrue(PyramidCourse.atBase(PyramidCourse.SIMON_TILE), "Simon stands at the base")
        assertTrue(!PyramidCourse.atBase(Tile(3355, 2833, 1)), "level 1 is not the base")
        assertTrue(!PyramidCourse.atBase(Tile(3041, 4695, 2)), "the upper pyramid is not the base")
    }

    @Test
    fun `the summit rocks are gated on the game's own pyramid-top flag`() {
        // Climbing them pays no XP and barely moves the player, so only the varbit can prove they were done
        // — and without them the Doorway hands over nothing.
        val rocks = PyramidCourse.ROUTE[PyramidCourse.ROUTE.size - 3]
        assertEquals("Climbing rocks", rocks.display)
        assertEquals(PyramidCourse.TOP_EARNED, rocks.doneVarbit)
        assertEquals("Doorway", PyramidCourse.ROUTE.last().display, "the rocks must come before the exit")

        // Its landing must not act as a resume anchor: it sits one tile from the previous step's.
        assertTrue(PyramidCourse.ROUTE.size - 3 !in PyramidCourse.anchors())
    }

    @Test
    fun `the pyramid is catalogued as a non-rooftop course`() {
        assertEquals(CourseStyle.PYRAMID, Course.PYRAMID.style)
        assertEquals(PyramidCourse.START, Course.PYRAMID.start)
        assertTrue(Course.PYRAMID.members, "the pyramid is members-only")
        assertEquals(30, Course.PYRAMID.level)
    }

    @Test
    fun `tops policy round-trips through its stored label`() {
        TopsPolicy.entries.forEach { assertEquals(it, TopsPolicy.of(it.label)) }
        assertEquals(TopsPolicy.SELL, TopsPolicy.of(""), "an unset value defaults to selling")
        assertNotNull(TopsPolicy.of("nonsense"))
    }
}
