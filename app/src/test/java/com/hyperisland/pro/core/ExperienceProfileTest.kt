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
            assertEquals(1.00f, g.scaleX.first()); assertEquals(1.00f, g.scaleY.first())
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
