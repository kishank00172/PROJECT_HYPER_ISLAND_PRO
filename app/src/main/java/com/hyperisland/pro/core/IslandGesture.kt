package com.hyperisland.pro.core

/**
 * What a touch on the island *means*, as a small state machine with no Android types in it.
 *
 * The bug this exists to kill: the old code decided tap-vs-swipe at ACTION_UP from one number only —
 * the distance between where the finger went down and where it lifted. A drag that curved back,
 * decelerated onto its starting point, or was simply short and fast (a flick) ended inside 10dp of the
 * start and was read as a tap. Opening a chat from a swipe is worse than missing a gesture, because
 * the user did not ask for anything and the island navigated away.
 *
 * So the machine latches: the moment accumulated travel exceeds the platform touch slop, the gesture
 * becomes a *drag* and a tap is no longer reachable, whatever the finger does afterwards. It also
 * keeps a smoothed velocity, because "premium" gesture recognisers commit on intent (fast flick =
 * swipe) and not on distance alone, and it exposes the live offset so the view can ride under the
 * finger instead of waiting for the lift.
 *
 * Axis is chosen once, at the moment of crossing the slop — the way ViewPager does — so a diagonal
 * cannot decide itself halfway through.
 */
class IslandGesture(
    /** ViewConfiguration.scaledTouchSlop: below this, it is still a tap. */
    private val touchSlopPx: Float,
    /** ViewConfiguration.scaledMinimumFlingVelocity, px/s. */
    private val minFlingPxPerS: Float,
    /** Horizontal travel (or flick) needed to commit a page change. */
    private val pageCommitPx: Float,
    /** Hard cap on how far the card may be dragged sideways; the neighbour stays a peek, not a flying page. */
    private val maxDragPx: Float,
    /** Cap on how far the card may be lifted. A lift is a dismiss *intent*, not a scroll, so it is short. */
    private val maxLiftPx: Float = maxDragPx,
    private val edgeDamping: Float = 0.4f,
    private val downDamping: Float = 0.25f
) {

    enum class Action { NONE, TAP, SWIPE_UP, PAGE_OLDER, PAGE_NEWER }

    /** Where the caller should draw the content right now. Both are already damped and capped. */
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    var dragging = false
        private set

    private var armed = false
    private var allowTap = true
    private var pagesEnabled = false
    private var atFirstPage = false
    private var atLastPage = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveMs = 0L
    private var travelledX = 0f
    private var travelledY = 0f
    private var totalX = 0f
    private var totalY = 0f
    private var velX = 0f
    private var velY = 0f
    private var horizontalAxis = false

    /**
     * @param allowTap false when a child view (an action button, the reply field) took the DOWN — then
     *   the island must not claim anything, only report NONE at the end.
     * @param pagesEnabled true only in the fully expanded card, where there is a ring to flip through.
     */
    fun begin(
        x: Float,
        y: Float,
        timeMs: Long,
        allowTap: Boolean,
        pagesEnabled: Boolean,
        atFirstPage: Boolean,
        atLastPage: Boolean
    ) {
        armed = true
        this.allowTap = allowTap
        this.pagesEnabled = pagesEnabled
        this.atFirstPage = atFirstPage
        this.atLastPage = atLastPage
        startX = x; startY = y; lastX = x; lastY = y; lastMoveMs = timeMs
        offsetX = 0f; offsetY = 0f
        travelledX = 0f; travelledY = 0f
        totalX = 0f; totalY = 0f
        velX = 0f; velY = 0f
        dragging = false
        horizontalAxis = false
    }

    /** Feed every ACTION_MOVE (and historical samples) here. Returns true if the view must move. */
    fun move(x: Float, y: Float, timeMs: Long): Boolean {
        if (!armed) return false
        val dx = x - lastX
        val dy = y - lastY
        val dt = timeMs - lastMoveMs
        if (dt > 0L) {
            // Smoothing keeps a single jittery frame from faking a fling.
            velX = velX * 0.5f + (dx / dt * 1000f) * 0.5f
            velY = velY * 0.5f + (dy / dt * 1000f) * 0.5f
            lastMoveMs = timeMs
        }
        lastX = x; lastY = y
        val totalX = x - startX
        val totalY = y - startY
        this.totalX = totalX
        this.totalY = totalY
        travelledX = maxOf(travelledX, kotlin.math.abs(totalX))
        travelledY = maxOf(travelledY, kotlin.math.abs(totalY))

        if (!dragging && (travelledX > touchSlopPx || travelledY > touchSlopPx)) {
            dragging = true
            horizontalAxis = travelledX >= travelledY
        }
        if (!dragging) return false

        if (horizontalAxis) {
            offsetX = if (!pagesEnabled) {
                clamp(totalX * 0.2f)
            } else {
                val atEdge = (totalX > 0f && atFirstPage) || (totalX < 0f && atLastPage)
                clamp(totalX * if (atEdge) edgeDamping else 1f)
            }
            offsetY = 0f
        } else {
            // Down is not a gesture this island has, so it barely moves; up is a real one.
            offsetY = if (totalY > 0f) totalY * downDamping else -kotlin.math.abs(totalY).coerceAtMost(maxLiftPx)
            offsetX = 0f
        }
        return true
    }

    /** Lift. Exactly one Action comes out; NONE means "spring the card back, nothing happened". */
    fun end(timeMs: Long): Action {
        if (!armed) return Action.NONE
        armed = false
        val wasDragging = dragging
        val horizontal = horizontalAxis
        val offX = offsetX
        val offY = offsetY
        // A finger that rested for a beat before lifting is a slow drag, not a fling.
        val stale = timeMs - lastMoveMs > 90L
        val vxE = if (stale) 0f else velX
        val vyE = if (stale) 0f else velY
        val netX = totalX
        val netY = totalY
        dragging = false
        offsetX = 0f; offsetY = 0f

        if (!wasDragging) return if (allowTap && travelledX <= touchSlopPx && travelledY <= touchSlopPx) Action.TAP else Action.NONE
        if (!allowTap) return Action.NONE

        return if (horizontal) {
            when {
                !pagesEnabled -> Action.NONE
                // A flick only counts when the finger actually ended up on that side: dragging out and
                // back is the user changing their mind, so it must spring back, not page.
                (offX <= -pageCommitPx || (vxE <= -minFlingPxPerS && netX <= -touchSlopPx)) && !atLastPage ->
                    Action.PAGE_OLDER
                (offX >= pageCommitPx || (vxE >= minFlingPxPerS && netX >= touchSlopPx)) && !atFirstPage ->
                    Action.PAGE_NEWER
                else -> Action.NONE
            }
        } else {
            val far = offY <= -pageCommitPx
            val flicked = vyE <= -minFlingPxPerS && netY <= -touchSlopPx
            if (far || flicked) Action.SWIPE_UP else Action.NONE
        }
    }

    /** ACTION_CANCEL, or a second finger landing: abandon silently, the caller springs the card back. */
    fun cancel() {
        armed = false
        dragging = false
        offsetX = 0f; offsetY = 0f
        velX = 0f; velY = 0f
    }

    private fun clamp(v: Float): Float {
        val cap = maxDragPx.coerceAtLeast(1f)
        return v.coerceIn(-cap, cap)
    }
}
