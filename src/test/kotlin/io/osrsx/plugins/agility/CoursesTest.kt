package io.osrsx.plugins.agility

import io.osrsx.api.player.Skill
import io.osrsx.api.platform.WorldInfo
import io.osrsx.testkit.TestContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoursesTest {

    /** A headless context reporting a fixed membership / Agility level for eligibility checks. */
    private fun ctx(members: Boolean, agility: Int): TestContext {
        val ctx = TestContext()
        whenever(ctx.worlds.current()).thenReturn(301)
        whenever(ctx.worlds.list())
            // Named args past `location`: the SDK inserted `regionName` before `members`, so positional
            // arguments silently bound the membership flag to a String parameter and stopped compiling.
            .thenReturn(
                listOf(
                    WorldInfo(301, 100, 0, members = members, pvp = false, highRisk = false, types = emptySet()),
                ),
            )
        whenever(ctx.skills.real(Skill.AGILITY)).thenReturn(agility)
        return ctx
    }

    @Test
    fun `labels are unique and carry level + membership`() {
        val labels = Course.entries.map { it.label() }
        assertEquals(labels.size, labels.toSet().size, "duplicate course labels")
        assertEquals("Varrock (30)", Course.VARROCK.label())
        assertEquals("Falador (50, P2P)", Course.FALADOR.label())
    }

    @Test
    fun `a brand new account has a course`() {
        val fresh = ctx(members = true, agility = 1)
        assertEquals(Course.DRAYNOR, Courses.resolveBest(fresh))
        assertEquals(listOf(Courses.BEST, "Draynor Village (1)"), Courses.optionsFor(fresh))
    }

    @Test
    fun `a course saved under its old level is still that course`() {
        assertEquals(Course.DRAYNOR, Courses.byLabel("Draynor Village (10)"))
        assertEquals(Course.DRAYNOR, Courses.courseFor(ctx(members = true, agility = 74), "Draynor Village (10)"))
    }

    @Test
    fun `courses are catalogued in ascending level order`() {
        val levels = Course.entries.map { it.level }
        assertEquals(levels.sorted(), levels, "courses should be ordered by level")
    }

    @Test
    fun `f2p low-level account sees only the courses it qualifies for`() {
        val options = Courses.optionsFor(ctx(members = false, agility = 30))
        assertEquals(Courses.BEST, options.first(), "BEST is always the first option")
        assertTrue(options.any { it.startsWith("Draynor") }, "level 1 F2P course should show")
        assertTrue(options.any { it.startsWith("Varrock") }, "level 30 F2P course should show")
        assertFalse(options.any { it.startsWith("Canifis") }, "members course should be hidden on F2P")
        assertFalse(options.any { it.startsWith("Seers") }, "level 60 course hidden at agility 30")
    }

    @Test
    fun `high-level members account sees every course`() {
        val options = Courses.optionsFor(ctx(members = true, agility = 99))
        assertEquals(Course.entries.size + 1, options.size) // every course + BEST
    }

    @Test
    fun `resolveBest picks the highest qualifying course`() {
        assertEquals(Course.VARROCK, Courses.resolveBest(ctx(members = false, agility = 35)))
        assertEquals(Course.SEERS, Courses.resolveBest(ctx(members = true, agility = 60)))
        assertEquals(Course.DRAYNOR, Courses.resolveBest(ctx(members = false, agility = 5)))
    }

    @Test
    fun `resolveBest never auto-picks the pyramid`() {
        // It qualifies at 30 on a members world but is a slower, gp-oriented course needing desert-heat
        // protection — it must only run when chosen by name.
        assertEquals(Course.VARROCK, Courses.resolveBest(ctx(members = true, agility = 35)))
        assertTrue(Courses.optionsFor(ctx(members = true, agility = 35)).any { it.startsWith("Agility Pyramid") },
            "it must still be offered in the dropdown")
    }

    @Test
    fun `courseFor resolves BEST and a specific label`() {
        val ctx = ctx(members = true, agility = 99)
        assertNotNull(Courses.courseFor(ctx, Courses.BEST))
        assertEquals(Course.FALADOR, Courses.courseFor(ctx, "Falador (50, P2P)"))
    }

    @Test
    fun `auto stays on the current course while on a roof after a level-up`() {
        // Level 20 unlocks Al Kharid mid-lap on the Draynor roof: Auto must finish Draynor first.
        assertEquals(Course.DRAYNOR,
            Courses.settle(Courses.BEST, Course.AL_KHARID, pinned = Course.DRAYNOR, onRoof = true))
        // Back on the ground the switch goes through.
        assertEquals(Course.AL_KHARID,
            Courses.settle(Courses.BEST, Course.AL_KHARID, pinned = Course.DRAYNOR, onRoof = false))
        // Nothing pinned yet (plugin just enabled): take the resolved course.
        assertEquals(Course.AL_KHARID,
            Courses.settle(Courses.BEST, Course.AL_KHARID, pinned = null, onRoof = true))
    }

    @Test
    fun `a course chosen by name is never overridden by the roof pin`() {
        assertEquals(Course.VARROCK,
            Courses.settle(Course.VARROCK.label(), Course.VARROCK, pinned = Course.DRAYNOR, onRoof = true))
    }

    @Test
    fun `the pyramid is never pinned`() {
        assertEquals(Course.VARROCK,
            Courses.settle(Courses.BEST, Course.VARROCK, pinned = Course.PYRAMID, onRoof = true))
    }
}
