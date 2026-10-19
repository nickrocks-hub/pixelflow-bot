# AI GAME SUBWAY SURFERS

An Android app that plays **Subway Surfers** (`com.kiloo.subwaysurf`) by itself, entirely on the phone (no internet, no cloud AI).

## How it plays

1. **Sees the screen** — screen capture at 360 px wide (`CaptureService`).
2. **Finds the tracks** (`core/TrackVision`) — every Subway Surfers world has the same three railway tracks.
   The steel rails show up as thin bright lines. The bot finds the vanishing point they meet at, then fits
   the three-track grid (two rails per track, rails 0.45 of a lane width apart).
3. **Spots obstacles** — walking each track's rails from the runner towards the horizon, the place where the
   rails disappear is the next train or barrier in that lane. Long blockages = trains, short ones = barriers.
4. **Decides** (`core/SurferBrain`) — turns screen rows into distance, measures game speed, and only reacts to
   things that are actually coming closer (so train roofs, sparkles and the runner don't fool it). If an obstacle in
   your lane is closer than the reaction time it moves to a clear side lane, otherwise jumps (or rolls, if set).
   On train roofs and during jetpack flights it holds still.
5. **Swipes** (`SwipeService`, an accessibility service) — left / right / up / down, only while Subway Surfers is the
   app in front. It never taps buttons, ads or purchases.

## Use

1. Install the APK from the latest *AI GAME SUBWAY SURFERS build* release on this repo.
2. Open the app → **Open Accessibility settings** → turn on *AI GAME SUBWAY SURFERS*
   (if blocked as a restricted setting: App info → ⋮ → Allow restricted settings).
3. **Start bot and open Subway Surfers**, share the entire screen, start a run in the game.
4. Use the floating START/STOP button to pause and resume.

Tuning sliders (reaction distance, lane-change margin, runner position…) are on the app's main screen.

## Testing without a phone

`tools/` holds desktop Java tools that run the same vision code on a screen recording:

```
ffmpeg -i rec.mp4 -vf fps=30,scale=360:-2 -f rawvideo -pix_fmt rgb24 rec.raw
javac -d out app/src/main/java/com/aigame/subwaysurfers/core/*.java tools/*.java
java -cp out Replay rec.raw 360 840 30 15           # decisions per frame
java -cp out DebugFrames out.png frame1.png frame2.png   # draws rails, lanes, obstacles
```

## Build

GitHub Actions builds the debug APK on every push to the `subwaysurfers` branch and attaches it to a release.
