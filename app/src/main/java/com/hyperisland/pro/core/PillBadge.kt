package com.hyperisland.pro.core

/**
 * The badge rule, in one place: nothing below two chats, the number from two up, and nothing in between.
 *
 * He set this himself ("ek number zaroori nahi, do se upar dikhao"), and it used to be written twice - once in
 * `updatePillBadge` and once inline wherever a preview needed the same decision - which is how a rule this
 * small becomes a disagreement between two screens. It lives here so the count property and the tests agree.
 */
object PillBadge {
    fun textFor(chats: Int): String = if (chats <= 1) "" else chats.toString()

    /** Below this the pill shows no digit at all, so the view is not even laid out for it. */
    fun isShown(chats: Int): Boolean = chats > 1

    /**
     * Apple's `contentTransition(.numericText())`: a number that changed does not blink, its digits travel in the
     * direction the value moved. 1 for up (a stack arriving), -1 for down (a stack being read), 0 for no change -
     * so the caller never animates a frame for a number that did not move, and the badge's own rule ("nothing
     * below two") stays the only reason it is not drawn.
     */
    fun rollDirection(from: Int, to: Int): Float = when {
        to > from -> 1f
        to < from -> -1f
        else -> 0f
    }
}
