# AI GAME BOT — agent platform architecture (v1.7)

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

## v1.7 — completely offline: on-device AI agent

The app has **no internet permission** (`tools:node="remove"` on INTERNET, ACCESS_NETWORK_STATE and
ACCESS_WIFI_STATE, so no library can merge them back). The v1.6 cloud client, API-key screen and cost model are deleted.

- `LocalVision.kt`: Google LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) running a `.litertlm`
  vision model on the phone (recommended: Gemma 3n E2B int4, ~3 GB, from huggingface.co/google/gemma-3n-E2B-it-litert-lm).
  One engine per process, GPU with CPU fallback, a fresh conversation per question. The screenshot goes to the
  app's private cache as a JPEG and is read by the model there.
- `ModelActivity` ("On-device AI"): import the model file with the system file picker (copied into app-private
  storage, free-space check, one model kept), test it (load time + reply time), delete it, GPU/CPU, step limit, settle time.
- `core/vlm/ScreenGrid`: small models can't give pixel coordinates reliably, so each screenshot (540 px wide) gets a
  labelled grid (9 columns A–I, square cells, name printed in each cell). The model answers `"at":"C7"`, `"to":"F7"`;
  once a board is calibrated and confirmed it can also use board cells `"r3c4"`. Raw `x`/`y` still accepted.
  Calibration reports the board as grid cells `from`/`to`; it's saved as `board` in the profile.
- `InstructionAgent`: same calibrate → confirm → play → learn-rules loop as v1.6, with a step limit and pace limit
  instead of a money budget.

Honest limits: a phone-sized model takes several seconds to tens of seconds per step, needs ~6 GB+ RAM, and is far
less capable than large cloud models; expect mistakes. Arm64 only (`abiFilters`).

Verified: `tools/core/VisionTests` (37 checks, scripted model: grid geometry, cell and board-cell actions, scaling,
refusals, step and pace limits, prompts, calibration, rule learning). Not verifiable here: real on-device inference
(needs the phone and the model file).

## Candy Crush board shapes (v1.6)
`candy/BoardShape` extends the detected grid to every island on the same lattice and finds narrow boards; synthetic test
(`tools/candy/CandyShapes`): original detector 5/10 shapes, now 10/10 with every cell right. Fixed a decompiled loop in
`CandySolver` that never exited, hanging the solver on any matching move.

## v1.5 — external games only
Built-in practice games (in-app Nonogram, Block Puzzle, Dodge) were removed at the user's request; the app
now only plays games installed on the phone that you launch from the library.
- Players: Nonogram.com (`NonogramMode`, `nono/*`) and Candy Crush Saga (`CandyMode`, `candy/*`, `libs/candy-vision.jar`,
  restored from build 17 and re-hosted on the panel without reflection).
- Library → profile → ▶ Launch & play: checks the tap service and screen capture (asks for capture), then opens the game.
  The floating panel follows the game in front (library games only) and never switches while a player is busy.
- A game you add without a player stays CALIBRATING; its panel says it can't play it and offers 📸 Save screen.
- A profile you added before its player existed is upgraded in place (your name and objective kept).
- Measured earlier with the (now removed) Dodge simulator: 50 ms of capture + tap lag already removes most of the
  skill in a reflex game, so real-time external games are not a realistic target for screen-capture play.

Status: Nonogram.com — verified on real frames (desktop) and used on the phone. Candy Crush — the same code that ran in
build 17, re-hosted; compiles, not re-tested on a phone in this build. Candy sessions log to Download/AIGameBot but
are not yet in the experience store. `learn/` (Q-learning core) is kept for future players but no game uses it now.
