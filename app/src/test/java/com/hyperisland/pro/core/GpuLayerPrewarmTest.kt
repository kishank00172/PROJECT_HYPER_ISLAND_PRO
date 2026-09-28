package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GpuLayerPrewarmTest {

    @Test
    fun detachedView_isSkipped_andNeverBuilds() {
        // the b1451 launch crash: band/body removed gridContentSec from the hierarchy -> parent == null
        assertEquals(GpuLayerPrewarm.Action.SKIP_PERMANENT,
            GpuLayerPrewarm.plan(viewExists = true, hasParent = false, attached = false, width = 0, height = 0, retryLeft = 1, alreadyDone = false))
        assertEquals(GpuLayerPrewarm.Action.SKIP_PERMANENT,
            GpuLayerPrewarm.plan(viewExists = false, hasParent = false, attached = false, width = 0, height = 0, retryLeft = 1, alreadyDone = false))
    }

    @Test
    fun attachedButZeroSize_defersOnce_thenSkips() {
        assertEquals(GpuLayerPrewarm.Action.DEFER,
            GpuLayerPrewarm.plan(true, true, attached = false, width = 0, height = 0, retryLeft = 1, alreadyDone = false))
        assertEquals(GpuLayerPrewarm.Action.SKIP_PERMANENT,   // one retry, never a loop
            GpuLayerPrewarm.plan(true, true, attached = false, width = 0, height = 0, retryLeft = 0, alreadyDone = false))
    }

    @Test
    fun validAttachedView_builds_andFlagMayFlip() {
        assertEquals(GpuLayerPrewarm.Action.BUILD,
            GpuLayerPrewarm.plan(true, true, attached = true, width = 1067, height = 104, retryLeft = 1, alreadyDone = false))
    }

    @Test
    fun onceFlag_neverBuildsAgain() {
        assertEquals(GpuLayerPrewarm.Action.ALREADY_DONE,
            GpuLayerPrewarm.plan(true, true, attached = true, width = 1067, height = 104, retryLeft = 1, alreadyDone = true))
    }

    @Test
    fun buildImpliesAttachedAndLaidOut_invariant() {
        // the invariant the service may rely on before calling View.buildLayer()
        for (attached in listOf(true, false)) for (w in intArrayOf(0, 10)) for (h in intArrayOf(0, 10)) {
            val a = GpuLayerPrewarm.plan(true, true, attached, w, h, retryLeft = 1, alreadyDone = false)
            if (a == GpuLayerPrewarm.Action.BUILD) assertNotEquals(false, attached && w > 0 && h > 0)
        }
    }
}
