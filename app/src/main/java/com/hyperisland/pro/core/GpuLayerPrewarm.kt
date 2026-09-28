package com.hyperisland.pro.core

/** b1452 hotfix: the GPU pre-warm decision, separated from android.view.View so the entire matrix is
  * testable on the JVM (this test suite deliberately has no Robolectric - see app/build.gradle).
  * Rule: buildLayer may only run on an attached, laid-out view; deferral is bounded to one retry and
  * never loops; a view with no parent is skipped permanently (the band/body rebuild of Round A2
  * deliberately removed gridContentSec from the hierarchy - that orphan is what crashed b1451). */
object GpuLayerPrewarm {
    enum class Action { BUILD, DEFER, SKIP_PERMANENT, ALREADY_DONE }
    fun plan(
        viewExists: Boolean,
        hasParent: Boolean,
        attached: Boolean,
        width: Int,
        height: Int,
        retryLeft: Int,
        alreadyDone: Boolean
    ): Action = when {
        alreadyDone -> Action.ALREADY_DONE
        !viewExists || !hasParent -> Action.SKIP_PERMANENT
        attached && width > 0 && height > 0 -> Action.BUILD
        retryLeft > 0 -> Action.DEFER
        else -> Action.SKIP_PERMANENT
    }
}
