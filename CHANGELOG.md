# Timepiece changelog

## v0.5.12

- Back up and restore alarms (Settings › Alarms): save every alarm to a file and bring them back after reinstalling or on a new phone; restoring skips alarms you already have

## v0.5.11

- A ringing alarm can always be stopped: if its notification is swiped away by accident it comes straight back with Dismiss and Snooze
- Opening Timepiece while an alarm or timer rings goes straight to the big Dismiss / Snooze screen
- Switching off or deleting an alarm while it rings stops it at once

## v0.5.10

- Bedtime reminder (Settings › Alarms): a gentle nudge at the time you pick, with your next alarm and how many hours of sleep you'd get if you went to bed now

## v0.5.9

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.5.8

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.7

- Widgets: choose the title colour in each widget's settings (automatic, white, dark, Aqua blue, Super pink or gold), on top of the background looks

## v0.5.6

- The “Upcoming alarm” notice no longer comes back after you swipe it away, or when you open the app
- A repeating alarm can no longer ring twice in the same minute

## v0.5.5

- Widgets can now match your look: new Bliss blue and Super neon backgrounds, and “Match my Wonderdesk look” follows whatever Control Room is set to

## v0.5.4

- Fixes from a full round of stress testing on every screen

## v0.5.3

- Alarms that Android might block are flagged at the top of Alarms: notifications off, ringing silenced, exact alarms or full-screen alerts not allowed, each one tap from the right switch
- New Settings row for full-screen alerts, which Android 14 and newer may not grant to apps from outside the Play Store
- Import from Clock: the greyed-out accessibility switch is now explained step by step (Android blocks it as a “Restricted setting” until you allow it in App info)
- Solve a sum to dismiss: an option per alarm for heavy sleepers
- The ringing screen follows your look
- Swipe an alarm right to turn it on or off, left to delete it, with Undo
- Tap the tab you're already on to glide back to the top of its list
- Nothing required stays hidden: if Android is blocking something the app needs, a slim banner says what and takes you straight to the switch, and Settings › Permissions and access lists everything with its state

## v0.5.2

- Alarms and timers glide into place when you add or remove them
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Clock widget fits any size, down to a 1×1 square
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Clock widget settings: any city's time zone, seconds, date, next alarm, Timer and Alarms buttons, and the background
- Fixed a crash with the same city added twice
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- New Clock widget showing the time, date and your next alarm, with Timer and Alarms buttons
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- Bring alarms over from your phone's Clock app: import the next alarm with one tap, or all of them (times, labels, repeat days and on/off) by scrolling Clock once
- Google Assistant and other apps can set alarms and timers in Timepiece
- Clocks and the stopwatch pause completely in the background and run smoothly on screen
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Alarms that ring on the lock screen with your choice of sound
- Repeat patterns, Skip next and Pause for today
- Timers, multiple stopwatches and a world clock
