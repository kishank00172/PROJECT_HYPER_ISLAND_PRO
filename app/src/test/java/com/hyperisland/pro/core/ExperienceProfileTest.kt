package com.hyperisland.pro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceProfileTest {

    @Test
    fun parse_defaultsUnknownToMix() {
        assertEquals(ExperienceVariant.MIX, ExperienceProfileTestV.of(null))
        assertEquals(ExperienceVariant.CLAUDE, ExperienceVariant.parse("claude"))
        assertEquals(ExperienceVariant.SOL, ExperienceVariant.parse("SOL"))
        assertEquals(ExperienceVariant.MIX, ExperienceVariant.parse("nonsense"))
    }

    @Test
    fun glowStopTables_areSameLengthAndMonotonic() {
        for (v in ExperienceVariant.entries) {
            val g = ExperienceProfiles.of(v).glow
            assertEquals(g.stopFractions.size, g.stopAlphaMul.size)
            assertEquals(0f, g.stopFractions.first())
            for (i in 1 until g.stopFractions.size) {
                assertTrue("${v.name}: fractions must ascend", g.stopFractions[i] > g.stopFractions[i - 1])
            }
            assertEquals(1f, g.stopFractions.last())
            assertEquals(0f, g.stopAlphaMul.last())
        }
    }

    @Test
    fun gulpTables_areSameLengthStartAtOneEndAtOne() {
        for (v in ExperienceVariant.entries) {
            val g = ExperienceProfiles.of(v).gulp
            assertEquals(g.times.size, g.scaleX.size)
            assertEquals(g.times.size, g.scaleY.size)
            // every table MUST settle at rest; the start differs by design:
            // SOL/MIX ease in from 1.0, but CLAUDE's literal spring(t) starts ALREADY absorbed (0.94) -
            // that hard initial absorb is his "absorb first" weight, not a bug.
            if (v == ExperienceVariant.CLAUDE) {
                assertEquals(0.94f, g.scaleY.first(), 0.001f); assertEquals(0.94f, g.scaleX.first(), 0.001f)
            } else {
                assertEquals(1.00f, g.scaleX.first()); assertEquals(1.00f, g.scaleY.first())
            }
            assertEquals(1.00f, g.scaleX.last()); assertEquals(1.00f, g.scaleY.last())
            assertTrue("${v.name}: absorb must exist mid-curve",
                (1 until g.scaleY.size - 1).any { g.scaleY[it] < 1.0f })
        }
    }

    @Test
    fun mix_isSolStructurePlusClaudeTouches() {
        val mix = ExperienceProfiles.MIX
        assertEquals(ExperienceProfiles.SOL.pager, mix.pager)
        assertEquals(ExperienceProfiles.SOL.handshake, mix.handshake)
        assertEquals(ExperienceProfiles.SOL.sparse, mix.sparse)
        assertEquals(18, mix.glow.blurPx)            // from Claude
        assertEquals(35, mix.gulp.hapticAtMs)        // from Claude
        assertEquals(ExperienceProfiles.SOL.glow.coreRadiusDp, mix.glow.coreRadiusDp)
        assertArrayEquals(ExperienceProfiles.SOL.gulp.scaleY, mix.gulp.scaleY, 0.0001f)
    }

    /** b1455 fidelity pass: CLAUDE's glow numbers are HIS literals, not my inventions -
      *  radius = 1.6 x the 24dp icon, and his stops are the FINAL alphas (0.55 at center). */
    @Test
    fun claudeGlow_matchesHisAuditVerbatim() {
        val g = ExperienceProfiles.CLAUDE.glow
        assertFalse("claude is a single layered bloom, not sol's two-layer pool", g.twoLayer)
        assertEquals("radius = 1.6 x 24dp icon, his formula", 38, g.coreRadiusDp)
        assertEquals("his stops are absolute final alphas -> no extra base multiplier", 1.00f, g.coreAlpha, 0.001f)
        assertEquals("center = 55% alpha exactly as quoted", 0.55f, g.coreAlpha * g.stopAlphaMul[0], 0.001f)
        assertEquals("18 px bloom (px, not dp)", 18, g.blurPx)
    }

    /** b1455 fidelity pass: CLAUDE's gulp IS his MotionVariant.spring(110ms, r=0.09, zeta=0.55) at 6%
      *  amplitude - absorb to 0.94, then the spring's 12.6% overshoot becomes a +0.76% rebound. The table
      *  must contain that rebound (a plain dip-and-return was my earlier mistake). */
    @Test
    fun claudeGulp_isHisSpringNotMyInvention() {
        val g = ExperienceProfiles.CLAUDE.gulp
        assertEquals(110, g.durationMs)
        assertEquals("6 percent absorb", 0.94f, g.scaleY.min(), 0.001f)
        val rebound = g.scaleY.max()
        assertTrue("spring overshoot must produce a +0.7 percent rebound, got " + rebound, rebound > 1.005f && rebound < 1.010f)
        assertEquals("values at the very start = full absorb", 0.94f, g.scaleY.first(), 0.001f)
        assertEquals(1.00f, g.scaleY.last(), 0.001f)
        assertEquals(35, g.hapticAtMs)
        assertEquals(g.times.size, g.scaleY.size)
        assertEquals(g.times.size, g.scaleX.size)
        assertEquals(110f, g.times.last(), 0.001f)
    }

    @Test
    fun wrongAuditClaims_areNotEncoded() {
        // things proved false against HEAD must not exist anywhere in these tables:
        // (a) no headH-pin logic exists here at all (v2 path already uses topAnchored=true)
        // (b) the gulp values here are the NEW experiment; the existing analytic v2GulpAnim stays untouched
        // (c) glow tables carry shapes only - colour extraction still comes from updateAmbientGlow,
        //     and its (colour, cx, cy) shader key is untouched by any profile
        assertFalse(ExperienceProfiles.CLAUDE.handshake.applyEndFrame)   // claude's own audit asked for
        // "reset end" removal too; we keep it OFF only in his profile per his docstring, and ON for SOL/MIX
    }
}

private object ExperienceProfileTestV { fun of(s: String?) = ExperienceVariant.parse(s) }
