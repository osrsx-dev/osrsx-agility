package io.osrsx.plugins.agility

import io.osrsx.api.scene.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the surveyed rooftop tables.
 *
 * These are recorded coordinates, so the failure mode is not a crash but a silently wrong course — the
 * runner following a table that no longer matches the world, or advancing to the wrong step. The
 * properties below are the ones that make advancement sound.
 */
class RooftopRoutesTest {

    @Test
    fun `falador is the surveyed 13-obstacle course`() {
        assertEquals(13, RooftopRoutes.FALADOR.size)
        assertEquals("Rough wall", RooftopRoutes.FALADOR.first().display)
        assertEquals("Edge", RooftopRoutes.FALADOR.last().display)
    }

    /**
     * The fork the two survey laps disagreed on — the whole reason a step holds a LIST.
     *
     * One lap took ledge 14922, the other 14923, and both continued into 14924.
     */
    @Test
    fun `the fourth ledge accepts either obstacle`() {
        val fork = RooftopRoutes.FALADOR[10]
        assertEquals("Ledge", fork.display)
        assertEquals(setOf(14922, 14923), fork.options.map { it.id }.toSet())
        assertEquals(
            setOf(Tile(3012, 3334, 3), Tile(3014, 3335, 3)),
            fork.options.map { it.tile }.toSet(),
        )
    }

    /** Either arm of the fork must advance to the SAME next step, or one of them derails the lap. */
    @Test
    fun `both arms of the fork advance to the same step`() {
        val route = RooftopRoutes.FALADOR
        val viaFirst = RooftopRoutes.indexOf(route, Tile(3012, 3334, 3))
        val viaSecond = RooftopRoutes.indexOf(route, Tile(3014, 3335, 3))
        assertEquals(viaFirst, viaSecond, "the two ledges are not the same step")
        assertEquals("Ledge", route[(viaFirst + 1) % route.size].display)
        assertEquals(14924, route[(viaFirst + 1) % route.size].options.single().id)
    }

    @Test
    fun `canifis is the surveyed 8-obstacle course`() {
        assertEquals(8, RooftopRoutes.CANIFIS.size)
        assertEquals("Tall tree", RooftopRoutes.CANIFIS.first().display)
        assertEquals("Pole-vault", RooftopRoutes.CANIFIS[5].display)
        // Recorded, not derived: the ids do not ascend along the route.
        val ids = RooftopRoutes.CANIFIS.map { it.options.single().id }
        assertTrue(ids != ids.sorted(), "ids happen to be sorted — check this was recorded, not generated")
    }

    @Test
    fun `the first three courses are recorded, obstacle for obstacle`() {
        assertEquals(
            listOf("Rough wall", "Tightrope", "Tightrope", "Narrow wall", "Wall", "Gap", "Crate"),
            RooftopRoutes.DRAYNOR.map { it.display },
        )
        // The Cable is the one the learned ring lost.
        assertEquals(
            listOf("Rough wall", "Tightrope", "Cable", "Zip line", "Tropical tree", "Roof top beams", "Tightrope", "Gap"),
            RooftopRoutes.AL_KHARID.map { it.display },
        )
        assertEquals(9, RooftopRoutes.VARROCK.size)
        assertEquals(RooftopRoutes.DRAYNOR, RooftopRoutes.of(Course.DRAYNOR))
        assertEquals(RooftopRoutes.AL_KHARID, RooftopRoutes.of(Course.AL_KHARID))
        assertEquals(RooftopRoutes.VARROCK, RooftopRoutes.of(Course.VARROCK))
    }

    /** A start on the obstacle's own tile gets the obstacle climbed by the walker's last click. */
    @Test
    fun `the first three courses walk to ground beside their first obstacle`() {
        listOf(Course.DRAYNOR, Course.AL_KHARID, Course.VARROCK).forEach { c ->
            val first = RooftopRoutes.of(c)!!.first().options.single().tile
            assertTrue(c.start != first, "$c walks onto its first obstacle")
        }
    }

    /** Each table starts where the plugin walks to, or it would arrive and find nothing to click. */
    @Test
    fun `every route starts within reach of its course's walk-to tile`() {
        RooftopRoutes.ALL.forEach { (course, route) ->
            val first = route.first().options.single().tile
            assertTrue(first.distanceTo(course.start) <= 8, "$course: first obstacle $first is far from ${course.start}")
        }
    }

    /**
     * No tile may appear in two steps.
     *
     * Advancement is `indexOf(tile) + 1`, so a tile shared between steps would resolve to the earlier one
     * and silently rewind the course — the same class of bug as the desyncing ring, just baked into data.
     */
    @Test
    fun `no tile is shared between steps in any route`() {
        RooftopRoutes.ALL.forEach { (course, route) ->
            val tiles = route.flatMap { it.options.map { o -> o.tile } }
            assertEquals(tiles.size, tiles.toSet().size, "$course reuses a tile across steps: $tiles")
        }
    }

    @Test
    fun `every route has usable ids and a ground-level entry`() {
        RooftopRoutes.ALL.forEach { (course, route) ->
            assertTrue(route.isNotEmpty(), "$course is empty")
            route.forEachIndexed { i, step ->
                assertTrue(step.options.isNotEmpty(), "$course step $i has no options")
                step.options.forEach { o -> assertTrue(o.id > 0, "$course step $i has a bogus id ${o.id}") }
            }
            // A course is entered from the ground and everything after is above it.
            assertEquals(0, route.first().options.single().tile.plane, "$course does not start on the ground")
            assertTrue(route.drop(1).all { s -> s.options.all { it.tile.plane > 0 } }, "$course dips to ground")
        }
    }

    /** An unrecorded course must fall through to the learned ring rather than half-follow a table. */
    @Test
    fun `courses without a survey have no route`() {
        assertNull(RooftopRoutes.of(Course.ARDOUGNE))
        assertTrue(RooftopRoutes.of(Course.CANIFIS) != null)
        assertNull(RooftopRoutes.of(null))
        assertTrue(RooftopRoutes.of(Course.FALADOR) != null)
    }
}
