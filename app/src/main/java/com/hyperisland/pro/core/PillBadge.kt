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
}
