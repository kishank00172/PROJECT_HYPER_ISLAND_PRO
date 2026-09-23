# What closes the card, what counts as my own echo, and how the trace stays readable

Everything below is read off the tester's own capture - `hip-log-20260923-163536.txt`, 574 lines, build b1396 -
because three of this round's four items were invisible without it. The rule each time: quote the line, name the
fault, write down what replaced it, and say what was deliberately *not* done.

## 1. A quick action has to end with the island back in pill form

> "maine quick action use kiya to usko receive karne ke baad island ko wapas pill banna chahiye tha na, wo nahi
> hua."

What the log shows is that the action path had **no closing trigger and no instrumentation**: there is not one
`[ACTION]` line in 574, and the only trace of the flow is the visible side of it, a reply:

```
#330 [REPLY] send tapped: pkg=com.instagram.android text=hiii
#332 [RING] dismiss: no page for key=com.instagram.android|thread|kish.ank001 ring=1
#333 [STAGE] STAGE3_FULL -> STAGE1_IDLE (MANUAL_USER)
```

`#333` is him collapsing it by hand. Two separate faults sit under that:

**(a) the card was left waiting for an event that never comes.** An earlier round removed the "tick, then hide the
island after 500 ms" timer for the right reason - the island vanished before the tick could be seen - and recorded
the replacement as *"if the app cancels the notification, the ring's own dismiss path closes the page, which is the
honest moment to collapse"*. Honest, yes. Sufficient, no: Instagram does not always cancel, and nothing else was
left to close the card.

**(b) the removal that did arrive, missed.** Pages are filed under the conversation key the extractor derived
(`com.instagram.android|sender|HANA` - visible as `key=` on the ingest line), while a removal arrives with the
status-bar key (`com.instagram.android|thread|kish.ank001`). The old code looked up one string, found nothing,
logged one line and returned. So the read chat stayed in the ring, the badge stayed up, and the card stayed open.

Now:

- `dismissConversationFromRing` falls back to the key stored *with the page* and says so
  (`dismiss: matched the stored notification key, not a conversation key (...)`). Same string, different field name -
  which is exactly the kind of thing that survives until someone reads a log.
- after an action fires, `markActionTakeoverWait(pkg)` records which app we expect, and the moment **that app's
  window comes to the front** the island collapses: `STAGE2_PING` while the ring has pages, `STAGE1_IDLE` when it
  is empty. The tap asked that app to do something, so its window arriving *is* the moment the island's job is
  done - a trigger from the world, not a delay we guess.
- not every action brings a window (`mark as read` is the obvious one), so the wait carries a 4 s bound and the
  bound closes the card too. It is a bound, not the mechanism, and it stands down when anything better has already
  happened: if the island is no longer the full card, somebody already put it away, and if the page on screen is
  not the one that was acted on, a new message has earned its own time. Both refusals log a line, because a
  timer that decides nothing must still explain itself.
- a reply keeps its existing 420 ms collapse (he accepted that two rounds ago) and arms the same wait as well,
  because sending a reply does not usually bring the chat app forward.
- the page is **not** deleted when the action lands. A chat removed because we *hope* the action worked is a chat
  the user cannot get back; that is how "messages were lost on a tap" started, so removal stays with the app's own
  cancel.
- every step writes `[ACTION]` - tap, package, ring size, what it is waiting for, and what closed it. An
  uninstrumented path is an undebuggable path, and "no log line at all" was this round's diagnosis.

## 2. My own reply came back at me as a card

```
#339 [INGEST] listener show com.instagram.android key=com.instagram.android|sender+title title='You' rule=1to1:you unread=1
#340 [INGEST] show com.instagram.android 'You' hiii
#341 [RING] new page com.instagram.android 'You' unread=1 ring=2
#342 [COUNT] 1->2 chats=2 page=1/2 unread=1 badge="2" cause=new
#343 [MORPH] start notify->ping
```

500 ms after the island collapsed, the pill re-lit and the badge counted my own sentence as a new message.

`ReplyEchoSuppressor.isMatch` opened with:

```kotlin
if (sentText.length < MIN_ECHO_TEXT) return false // "ok", "hi", 👍 match half the language
```

`hiii` is four characters. That line rejected the entry before any rule looked at it, and both rules that could
have caught the echo never ran: the Instagram text rule (its window was 2 500 ms, the echo landed at **4 921 ms**)
and the self-marker tier (unreachable after the return). The length floor exists for a good reason - replying `ok`
used to delete every incoming chat that contained `ok`, which is why whole stretches of his Instagram DMs once
never reached the island - but it was being applied to the one signal that does not depend on my text at all.

The order now:

1. **text must match as a whole** - the incoming message or the MessagingStyle latest message, never a substring;
2. **self marker** - the notification title is exactly `You`/`Me`, or the body opens with `You:`/`You `/`Me:` -
   suppress at *any* length, inside the 12 s retain window. The app is announcing the sender; that is not a claim
   about my sentence, so a short reply cannot defeat it;
3. **then** the length floor, for the tiers that rest on my text alone: same-thread exact text, same notification
   key, and the Instagram override - whose window went 2.5 s -> 6 s, because 4.9 s is what the phone actually did.

Cost, in his terms: a real message is dropped only if it equals, whole, what I sent seconds ago *and* is titled
You/Me or sits in the thread I just replied from. `a real short message in the same thread is not my echo`,
`containment is not a match` and `the memory is one-shot so the next real message survives` are the tests holding
that line, and the suppressor now writes `[ECHO] dropped own reply ... age=4921ms` into the same log, so a
suppression is never invisible - the other half of "a message vanished and nobody knew why".

## 3. The trace: readable, and not at the cost of frames

> "log ko main theek se padh bhi nahi paa raha hu ... kabhi upar ja rha hai kabhi niche, swipe karta hu to force
> swipe ho jata hai"
> "koi change hua tabhi log run ho ya hamesha chalta rehta hai"

**What it is**: a tail of a buffer the service writes to for as long as it is alive - one line per decision
(`BOOT`, `DISPLAY`, `INGEST`, `RING`, `COUNT`, `STAGE`, `MORPH`, `TOUCH`, `ACTION`, `ECHO`, `REPLY`, `TRACE`) plus
the sampler's own periodic lines (`STALL`, `FRAME`). It does not start on a change and it does not stop; that is
why lines appear when nothing was done, and his file had **239 of 574** lines of exactly that.

**(a) the noise has a source, and the source is my instrumentation.** `checkNotificationShadeState()` does
`getWindows()` plus a `getRoot()` per system window - binder round trips, on the main thread - from
`onAccessibilityEvent`, and that event fires for every window change on the phone (IME, app switches, heads-up).
The 220 `[STALL]` lines are it. It is not only a log problem, and this is the proof:

```
#335 [STALL] 24ms main thread ... getRoot@AccessibilityWindowInfo.java:240 | us: checkNotificationShadeState@HyperAccessibilityService.kt:848
#336 [MORPH] end ... max=24ms@t=0.76 slow=1
```

The instrument put a 24 ms block inside the collapse it was measuring, at 76 % of its travel - the jank he keeps
reporting, manufactured by the code added to diagnose jank. The probe now runs only for events that could even be
the shelf (package `com.android.systemui`, or a class name containing shade/notification/panel) and at most twice a
second, with the verdict cached in the field it already had. It is *nested inside* the window-type branch rather
than an early `return` from `onAccessibilityEvent`: an early return would also skip the ingestion fallback below
it, and that is a behaviour change dressed up as a performance fix.

**(b) the viewer stopped fighting the finger.** The old `render()` handed the new text to the TextView, restored a
scroll offset captured *before* the swap, and then ran a corrective `fullScroll` inside a `post` - a frame later.
That pair is the teleport, and writing `scrollY` mid-drag is the forced swipe. Now: the tail only moves while the
view is at the bottom, nothing touches it between `ACTION_DOWN` and `ACTION_UP`, and while he is reading the text
is **frozen** (the status line counts what arrived meanwhile; TAIL resumes following). The buffer and the export
keep taking lines the whole time, so freezing the view costs no data.

**(c) runs and chatter.** Consecutive lines with the same body read as one line with a count (`... (x9)`) - the
sampler writes the same stack every window while a path stays slow, so repetition measures duration, not events -
and the sampler tags are hidden behind `QUIET`, on by default. Both are display-only: hiding lines in the buffer is
the same sin as the old silent 1500-line cap. `TraceLog.collapseRuns`, `tagOf`, `isChatter` and `tailLines` are
pure and JVM-tested, and the comparison that merges a run ignores the timestamp and the `#seq` so time order
survives.

## 4. What this document is not

Not a claim that any of it *feels* right - that is still his, on the device, which is why the styles, the entry,
the ride, the roll and the exit deadline are TestLab controls. What is claimed here is mechanism: a key that used
to miss, a guard that ran too early, an unbounded probe, and a viewer that wrote over a gesture. All four were
visible in his log and none of them were visible in mine.
