# AI GAME BOT — agent platform architecture (v1.4)

## Stage 1 — what existed and is reused
| Component | Where | Reused as |
|---|---|---|
| Screen capture (MediaProjection, fresh-frame requests) | `CaptureService` | Observation input for every external game |
| Accessibility gestures + floating panel | `TapService`, `NonogramMode.dispatch` | Control; `GestureExecutor` generalises it |
| Nonogram.com reader (grid, clues incl. two-digit/frozen, cells, red marks, hearts, fill switch; Challenge boards) | `nono/NonoVision`, `NextButton` | Perception of the `nonogram-com` adapter, unchanged |
| Exact nonogram solver | `nono/NonoSolver`, `game/nonogram/LineSolver` | Planner for Nonogram.com; baseline + puzzle validation |
| Built-in Nonogram (rules, seeded unique puzzles, train/eval split, env) | `game/nonogram/*` | Simulator of the `nonogram-sim` adapter |
| Learning core (linear Q-learning, replay, modes, model versions) | `learn/*` | Policy learning for any adapter with features |
| Auto next level | `AutoNext` | Level-transition handling |

## Shared core (`core/`, plain Java, desktop-tested)
| Concern | Class |
|---|---|
| Game profiles (versioned, history kept) | `GameProfile`, `ProfileStore` |
| Visual state extraction → structured state with confidence; OBSERVED / INFERRED / HIDDEN facts | `Perception`, `StateObservation` |
| Actions: tap, long press, swipe, drag path, hold/release, multi-stroke, macro record | `GameAction` |
| Action execution | `ActionExecutor` (Android: `GestureExecutor`) |
| Reward + progress detection, every reward names its source | `RewardModel` |
| Experience storage (state, action+timing, next state, reward+source, episode boundary, confidence, profile version) | `Experience`, `ExperienceStore`, `EpisodeRecorder` |
| When to stop acting | `SessionGuard` |
| Known adapters + shipped profiles | `Adapters` |
| Model versions, stable mark, rollback | `learn/ModelStore` |

Adapters: `nono/NonoComAdapter` (Nonogram.com perception + reward), `game/nonogram/NonoSimAdapter` (built-in game).

## Stage status
**Implemented and verified (desktop tests on the same Java, real phone screenshots):**
- Profiles: create, version, history, restore, refuse deleting shipped profiles (`tools/core/CoreTests`).
- Observations: Nonogram.com frames → structured state; Challenge board read in full; Level 8's 4 frozen clues marked HIDDEN; a non-game screen → UNKNOWN, confidence 0.
- Rewards from Nonogram.com's real signals (filled cells read back, red marks, hearts, Level Completed), each with a source.
- Experience store schema and episode boundaries; summary; clear.
- Session guard: stop, capture lost, control lost, other app in front, low confidence, non-gameplay/blocked screen, no-effect loop.
- Model stable mark + rollback.
- Built-in Nonogram rules/puzzles unchanged (bench tests pass).

**Implemented but unverified on a phone:**
- Nonogram.com sessions written to the experience store (START, one STEP per board check with the gestures since the last check as a macro action, END with reason).
- Game library, Add Game (installed apps), profile screen (objective, pace, experience, export/delete, versions), Mark stable / Roll back buttons.
- `GestureExecutor` (tap/long press/swipe/drag/hold-release/multi-touch): compiles against Android 34; not used by any game yet — Nonogram.com keeps its own proven gesture code.

**Requires configuration or external compute:** nothing in this stage.

**Not yet supported:**
- Games other than Nonogram: added games stay in CALIBRATING; nothing reads or controls them.
- Control calibration, rule hypotheses, demonstrations (stage 4); vision-language model backend (stage 5, opt-in, none connected);
  second game with different mechanics (stage 6); capture-rate/latency check for real-time games.

## Build & verify
```
S=app/src/main/java/com/nickrocks/pixelflowbot
javac -d /tmp/ct $S/core/*.java $S/learn/*.java $S/game/nonogram/*.java $S/game/nonogram/agent/*.java \
  $S/nono/*.java $S/vision/Frame.java tools/core/CoreTests.java tools/learn/NonogramGameBench.java tools/nono/NonoProbe.java
java -cp /tmp/ct CoreTests tools/nono/samples        # core + adapters
java -cp /tmp/ct NonogramGameBench 3000              # built-in game rules + learning benchmark
java -cp /tmp/ct NonoProbe tools/nono/samples/*.png  # screen reader on saved frames
```
APK: GitHub Actions `build.yml` (workflow_dispatch on branch `nonogram-mode`).

## Stage 6 — learning on two more games with different mechanics (v1.4)
Both are in-app simulators so they can train fast and be measured on held-out games. They share
`core/LabGame` + `core/Lab` (one training/evaluation path), `LabRuntime` (models, training thread,
evaluation) and `LabActivity` (play, watch, train, evaluate, versions, rollback).

| Game | Mechanic | Pace | Learner inputs |
|---|---|---|---|
| Block Puzzle (`game/blocks`) | place pieces on 8×8, clear rows/columns | turn-based | 15 measurements of the board each placement would leave |
| Dodge (`game/dodge`) | steer between lanes, field speeds up | real-time, 20 ticks/s | 14: obstacle distance in the target lane, drop timing, open neighbours, speed |

### Measured (desktop, same Java, `tools/lab/LabBench`; 100 held-out games; ± = 95% interval)
Block Puzzle, score (2 training runs, 1000 training games each):

| Player | Score |
|---|---|
| Random | 56 ± 6 |
| Hand-written rule | 976 ± 131 |
| Learner, untrained (ties → first move) | 141 ± 13 |
| Learner, 1000 games, run 1 / run 2 | 1107 ± 165 / 1023 ± 151 (best checkpoint 1208 ± 170) |

Dodge, seconds survived (60 s = win; 2 runs, 1500 training games each):

| Player | Seconds |
|---|---|
| Random | 3.7 ± 0.1 |
| Never move | 5.3 ± 0.4 |
| Hand-written rule | 17.2 ± 1.8 |
| Learner, untrained | 4.7 ± 0.4 |
| Learner, 1500 games, run 1 / run 2 | 24.2 ± 2.3 / 24.0 ± 2.2 (checkpoints ranged 16–29 s) |

Dodge with simulated screen-capture + tap lag (seconds survived):

| Lag | Rule | Learner trained without lag | Learner trained with that lag |
|---|---|---|---|
| 0 ms | 17.2 | 24.2 | 24.2 |
| 50 ms | 6.1 | 3.6 | 9.9 |
| 100 ms | 6.3 | 3.5 | 5.3 |
| 200 ms | 6.3 | 3.4 | 8.6 |
| 500 ms | 6.2 | 3.4 | 5.0 |

What this shows, and doesn't:
- The same learning code improved from near-random to above the hand-written rule on two games with
  different mechanics. On Block Puzzle the gain over the rule is within the error bars; on Dodge it is clear.
- Training is not monotonic (Dodge checkpoints 16–29 s): that is why models can be marked stable and rolled back.
- Lag matters more than the policy for reflex games: 50 ms already removes most of the skill. A phone bot
  watching a real reflex game through screen capture is unlikely to work unless capture + tap latency is far
  below that. Turn-based games are unaffected.
- No game reaches the 60 s / 300-placement caps reliably; the linear model judges one move at a time.

## What generalizes (after stage 6)
| Component | Generalizes? |
|---|---|
| Profiles, observations with sources/confidence, actions, experience store, session guard, model versions/rollback | Yes — used by all 4 games unchanged |
| Training/evaluation (`Lab`, `LabRuntime`), held-out seeds, baselines interface, UI (`LabActivity`) | Yes for simulated games — Block Puzzle and Dodge share all of it |
| Linear Q-learning (`learn/`) | Yes, the algorithm; per game you set learning rate/discount/exploration |
| Features (what the learner looks at) | No — written per game (15 for Block Puzzle, 14 for Dodge, 59 for Nonogram) |
| Perception from real screens | No — only Nonogram.com has a screen reader |
| Nonogram (built-in) training screen | Still its own (`NonogramActivity`, older than `Lab`) |

## Stage status (v1.4)
- Implemented and verified (desktop): both new games' rules, seeds, latency simulation, training,
  deterministic evaluation (`tools/lab/LabTests`, 20 checks; `CoreTests`, 50 checks), the benchmarks above.
- Implemented, unverified on a phone: `LabActivity` (touch play, watching, training, evaluation, versions),
  experience recording of every game there (yours as USER demonstrations).
- Not yet: learning from demonstrations (they're recorded only), screen perception for any game but
  Nonogram.com, the on-phone latency measurement, any vision-language model.
