# Nick Rocks AI Bot — autonomous gameplay

An Android app with an AI that plays Nick Rocks' twin-stick roguelike on the same phone. It watches the
screen, reads the game, decides, and works the game's own MOVE and SING joysticks and action buttons.
Everything runs on the phone, offline. Built for the Galaxy Z Fold 7 (unfolded), works on other screens
after calibrating the controls.

This is a separate app because the game's source code wasn't available: the bot plays the real game
from the outside, the way a player does, and sees only what's on screen.

## Using it

1. Install the APK from the latest `nick-rocks-ai-bot-build-N` release.
2. Open **Nick Rocks AI Bot** → **Start AI Run**. Turn on the accessibility service when asked
   (Settings → Accessibility → Installed apps → Nick Rocks AI Bot), then press Start again.
3. Allow screen sharing ("entire screen", or just the game). The game opens if the bot knows which app
   it is; otherwise open it yourself. Start a dungeon run: the AI takes over when it sees your hearts
   and your cowboy.
4. The floating panel (top of the screen) has **START/STOP** and **pause**. The run ends on death, victory,
   Stop, or an error, and the summary opens.

**Calibrate controls** (main screen) if the game's Touch Layout was changed or you play on the cover
screen: drag the circles onto MOVE, SING, the 4 buttons and the ULT star; tap B1–B4 to set their jobs.

## Menu (AI BOT — AUTONOMOUS GAMEPLAY)

Start AI Run · Stop AI Run · Pause / Resume · AI Settings · Run History · View Logs · AI Statistics.

## How it works (`app/src/main/java/com/nickrocks/aibot/core`, plain Java)

| Part | What it does |
|---|---|
| `GameStateObserver` | Reads each frame (shorter side 432 px): hearts (half hearts), ULT bar and star, minimap (current/visited/unexplored rooms), pause menu, red damage flash, floor vs walls (walk grid), the cowboy (red plaid under a black hat), and everything else on the floor. Objects are tracked frame to frame with the camera's own scrolling removed: things that move are enemies, small fast things are projectiles (notes that leave the player are yours), still things are furniture. |
| `NavigationPlanner` | Path finding on the walk grid (avoids walls, furniture, enemies and spots where it got stuck), picks the exit towards the nearest unexplored room on the minimap, sweeps rooms when no unexplored rooms are left (to find the way to the next floor), detects being stuck and side-steps. |
| `CombatDecisionSystem` | Predicts every enemy projectile's path and scores 16 directions + standing still (projectile danger, keep-away from enemies weighted by learned danger, walls, the objective). Picks targets (danger, distance, clear shot, boss), leads moving targets, and decides on DIVE / SMASH / DROP / SCREAM / ULT. |
| `AutonomousAgent` | The brain: objective (fight, hold, loot, explore, button test), movement, aim, abilities; counts kills, hits, damage, items, rooms, floors; writes explained decisions (situation, decision, reason, result, assessment) with the real numbers that decided them. |
| `RunSessionManager` | IDLE → INITIALIZING → RUNNING ⇄ PAUSED → FINALIZING → COMPLETED, plus ERROR and INTERRUPTED. Detects death, victory, stop and errors; finishes each run exactly once; in-game timer stops while paused. |
| `RunLogger`, `RunHistoryRepository` | Log written as it happens (events.jsonl appended every second, meta.json every 4 s) in app storage. Runs left RUNNING by a crash are marked INTERRUPTED on next start. Search, compare, delete, export JSON/CSV/TXT to Downloads/NickRocksAI. |
| `Learner` | Kept between runs: strategy variants chosen by UCB1 on run scores (Adaptive skill), enemy danger per enemy look, value of each button, measured walk and note speed, which button is DIVE (found by a button test), and repeated mistakes. |
| `ControlService` (Android) | Holds both joysticks with continued gesture strokes (thumbs stay down), taps buttons, floating panel, debug overlay (pure green; the reader ignores those pixels), calibration. |
| `CaptureService` (Android) | Screen capture on a background thread, throttled to the decision rate; only plays while the game is in front. |

### What it can't know

- Upgrade/shop/choice screens and the death and victory screens weren't in the recording, so the bot
  can't read them. Death is detected from the hearts (and the screen changing right after the last hit);
  victory is assumed when the game leaves the dungeon screen shortly after a boss falls.
- Damage dealt is counted in **hits** (notes that vanish on an enemy), not damage numbers.
- Which button is DIVE is found by a button test; SMASH/DROP/SCREAM are guessed from the icons and can be
  changed in calibration.

## Testing on a computer

```
javac -d out app/src/main/java/com/nickrocks/aibot/core/*.java tools/*.java
java -cp out VisionCheck tools/samples            # screen reader on real game frames
java -cp out SimTests scenarios simwork            # 12 scenarios in the simulator
java -cp out SimTests train simwork 100           # training mode: does learning help?
java -cp out VisionReplay <framesDir> 20 <outDir>  # annotate a whole recording
```

`tools/Sim.java` is a small dungeon game of the same shape (rooms, doors, minimap, hearts, bats, mages,
turrets, demons, a boss, projectiles, abilities, ULT, stairs). It produces the same observations as the
screen reader, so the brain, logs, session and learning are tested end to end, and every scenario checks
the log against what really happened in the game.
