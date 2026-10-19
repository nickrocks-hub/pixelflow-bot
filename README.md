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

Tuning sliders (reaction distance, phone delay, lane-change margin, runner position…) are on the app's main screen.
Drag the floating panel by its ✥ handle to move it.

## Control loop (v1.6)

`core/SurferBrain` keeps timestamped state and plans every frame:

- **Game state** PLAYING / STOPPED / NO_TRACKS from the track area's frame-to-frame motion and track detection;
  input only while playing, stops the moment the picture stops moving. The crash moment feeds the learner.
- **Lane** L / M / R or transitioning. The camera settles in one of three places (middle track at -0.71 / 0 /
  +0.71 lane widths from centre), so the grid fit is restricted to those (stable in busy scenes) and the winner
  *is* the lane reading. Lane swipes are confirmed from fresh frames; if the camera stays put the swipe was
  ignored or bounced, the state is corrected and the route replanned.
- **Vertical** GROUNDED / JUMPING / ROLLING from delivered gestures; ROOF and FLYING (jetpack) from camera height.
- **Obstacles** tracked with ids across frames; speed is a line fit over the last 8 sightings, so jitter isn't
  mistaken for motion; oncoming trains (faster than the scenery) get their own time to impact.
- **Timing**: frame age, perception, gesture delivery and lane-change times are measured; decisions from frames
  older than 150 ms are dropped.
- **Planning**: stay / one lane / two lanes / jump / roll; a target lane must be train-free for a while after
  arrival, every lane on the way must have no train alongside or arriving; barriers are jumped once per id at a
  calibrated lead; coins only when safe; commitment and hysteresis against oscillation.

`SwipeService` is the single owner of input: a queue of at most two moves, minimum gap between strokes, stale
moves dropped, straight single-axis strokes, and one reported result per move (delivered / cancelled / refused /
dropped).

`tools/ScenarioTest` feeds hand-made scenes to the planner: clear path, train ahead, low barrier, roll setting,
blocked neighbour, boxed in, two-lane move, ignored swipe, stale frames, faster approach, game over.

## Learning

After every crash (`core/Learner`) the bot looks at its last ~1.5 s of decisions and blames one cause —
changed lane into danger, jumped too early / too late, misread a train as a barrier, got boxed in, hesitated,
or didn't react — then nudges the matching setting (reaction distance, jump timing, how clear a lane must be,
train/barrier threshold) by a small bounded step. It remembers the settings of its longest run and returns to
them if three runs in a row are much worse. State is saved on the phone; "Reset learning" in the app clears it.
`java -cp out LearnerTest [log.zip crashMs]` checks the rules and replays a real crash.

## Logs

After each run (crash / game over) and when you press STOP, the app saves
`Download/AI GAME SUBWAY SURFERS/subway_<date>_<n>_<reason>.zip` with `log.txt` (one line per frame),
`info.txt` (phone + settings) and the last 8 seconds of frames. Replay one on a computer with
`java -cp out ReplayZip subway_....zip`.

## Testing without a phone

`tools/` holds desktop Java tools that run the same vision code on a screen recording:

```
ffmpeg -i rec.mp4 -vf fps=30,scale=360:-2 -f rawvideo -pix_fmt rgb24 rec.raw
javac -d out app/src/main/java/com/aigame/subwaysurfers/core/*.java tools/*.java
java -cp out Replay rec.raw 360 840 30 15           # decisions per frame
java -cp out DebugFrames out.png frame1.png frame2.png   # draws rails, lanes, obstacles
```

## Build

GitHub Actions builds the release APK (signed with the committed `app/release.keystore`, so updates install over each other) on every push to the `subwaysurfers` branch and attaches it to a GitHub release.
