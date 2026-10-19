# Rider Bot

An Android app that plays **Rider** (Ketchapp, `com.ketchapp.rider`) for you.
It looks at the screen, finds the car and the neon track, and presses/holds the
screen exactly like a finger would:

- **On the track** → hold (accelerate).
- **In the air** → hold to backflip *only if* the estimated time until landing
  leaves room for a full turn plus a safety margin; let go just before the car
  is upright again so it lands on its wheels.
- **Car gone** (crash screen, menus) → let go and wait. As soon as you start a
  new run, it picks the car up again automatically.

Everything runs on the phone. No internet, no cloud.

## Build & install

1. Open the `RiderBot` folder in **Android Studio** (Koala or newer).
2. Let Gradle sync (AGP 8.7.3, Gradle 8.11.1, compileSdk 35, Java 17).
3. Plug in your phone with USB debugging on and press **Run**, or build an APK:
   `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

Requires Android 8.0+.

## First run on the phone

1. Open **Rider Bot** → *Open Accessibility settings* → turn on **Rider Bot**.
   (Android 13+ may say it's a *restricted setting* for side-loaded apps: go to
   App info → ⋮ → *Allow restricted settings*, then try again.)
2. Tap **Start bot and open Rider**, allow screen sharing (**entire screen**).
3. Rider opens with a small floating panel at the top right: **START / STOP**
   plus a live status line (`GROUND`, or `AIR dev … land …s`).

## Tuning (in the app)

| Setting | Default | If… |
|---|---|---|
| Reaction latency | 90 ms | the car over-rotates past upright → raise; it lets go too early → lower |
| Safety margin before landing | 160 ms | it crashes starting flips it can't finish → raise |
| Upright tolerance | 28° | how tilted a landing still counts as OK |
| Do backflips / Max flips per jump | on / 0 (unlimited) | turn off flips for a pure survival run |
| Touch position | 75 % height | where the bot presses |

## How it works

`app/src/main/java/com/riderbot/app/`

- `core/RiderVision.java` finds the car: the largest long, thin blob of
  car-green, with its axis from PCA. It finds the track by the bright, thick
  core of the neon line, magenta or green. Pure white (wheels, trails, score
  text) is ignored. The top 11 % of the screen (score HUD and the floating
  panel) is skipped.
- `core/RiderBrain.java` tracks the car's full 0–360° angle frame by frame.
  It decides "on track" by whether there's thick track just beyond the wheels,
  and estimates time to landing from the height above the track and how fast
  that height is changing. From that it chooses hold or release.
- `CaptureService.java` mirrors the screen at 360 px wide with MediaProjection
  and runs every frame through the brain in about 1–2 ms.
- `BotAccessibilityService.java` presses and holds with continued gesture
  strokes, and draws the floating panel.

The two `core` classes are plain Java with no Android imports. They were tested
on a 30-second screen recording of the game. Across 900 frames the car was
missed only in short gaps (0.3 s at most): the white flash on a perfect
landing, crossing green track sections, and after the crash. During a gap
the bot keeps doing what it was doing. Air vs ground was right throughout, the angle tracked
correctly through full backflips, and landing-time estimates on the way down
were within about 0.1–0.2 s.

### Replaying a recording on a computer

```
ffmpeg -i recording.mp4 -vf "fps=30,scale=360:-2" -f rawvideo -pix_fmt rgb24 frames.raw
javac -d out app/src/main/java/com/riderbot/app/core/*.java tools/ReplayTest.java
java -cp out ReplayTest frames.raw 360 <height printed by ffmpeg> 30
```

Each output line shows ground/air, angle, predicted landing time and the
hold decision for that frame.

## Limits

- The colour rules are tuned to the default neon look in your recording, with
  magenta and green track. If a theme or car skin changes the colours, adjust
  `isCarColor` / `isTrackColor` in `RiderVision.java`.
- On green track sections the car can blend into the track. The bot then
  keeps holding until the car is visible again, which is right on those ramps.
- The bot doesn't press menu buttons or ads. You start each run, and it takes
  over as soon as the car is on screen.
