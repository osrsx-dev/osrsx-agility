package io.osrsx.plugins.agility

import io.osrsx.api.Skill
import io.osrsx.api.WorldInfo
import io.osrsx.testkit.TestContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoursesTest {

    /** A headless context reporting a fixed membership / Agility level for eligibility checks. */
    private fun ctx(members: Boolean, agility: Int): TestContext {
        val ctx = TestContext()
        whenever(ctx.worlds.current()).thenReturn(301)
        whenever(ctx.worlds.list())
            .thenReturn(listOf(WorldInfo(301, 100, 0, members, false, false, emptySet())))
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
    fun `courses are catalogued in ascending level order`() {
        val levels = Course.entries.map { it.level }
        assertEquals(levels.sorted(), levels, "courses should be ordered by level")
    }

    @Test
    fun `f2p low-level account sees only the courses it qualifies for`() {
        val options = Courses.optionsFor(ctx(members = false, agility = 30))
        assertEquals(Courses.BEST, options.first(), "BEST is always the first option")
        assertTrue(options.any { it.startsWith("Draynor") }, "level 10 F2P course should show")
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
        assertNull(Courses.resolveBest(ctx(members = false, agility = 5)), "no F2P course below level 10")
    }

    @Test
    fun `courseFor resolves BEST and a specific label`() {
        val ctx = ctx(members = true, agility = 99)
        assertNotNull(Courses.courseFor(ctx, Courses.BEST))
        assertEquals(Course.FALADOR, Courses.courseFor(ctx, "Falador (50, P2P)"))
    }
}
