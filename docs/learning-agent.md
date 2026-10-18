# Learning agent (v1.0)

## What already existed and is reused
- Observation: user-authorised screen capture (MediaProjection, "Entire screen"), fresh-frame requests.
- Control: user-enabled Accessibility service: taps, swipes, pinches; foreground-app check.
- Floating panel (Start/Pause/STOP), logs in Download/PixelFlowBot, auto-saved screenshots on misreads.
- Nonogram.com reader (grid, cells, clues incl. two-digit and frozen ❄, hearts, red mistake marks, fill switch).

## Architecture (app/src/main/java/com/nickrocks/pixelflowbot/learn = game-independent core)
| Part | Where |
|---|---|
| Observation | CaptureService (screen) — NonoAiMode.observe() |
| State extraction | nono/NonoVision → learn/nono/NonoState (hidden clues stay null; confidence from digit match) |
| Action selection | learn/Agent (ε-greedy over learn/LinearQ values of each candidate) |
| Action execution | Accessibility tap (NonoAiMode.tap), fill-mode switch handled first |
| Reward | learn/nono/NonoAdapter constants: fill +1, mistake −5, solved +10, out of hearts −10 |
| Experience storage | learn/Transition + learn/ExperienceLog (JSONL, rotates at 8 MB) + learn/ReplayBuffer |
| Training | learn/Agent.record → LinearQ.update (Q-learning) + replay batch |
| Model storage | learn/ModelStore: model_vN.json versions, model_latest.json autosave, import/export |
| Game-specific | learn/GameAdapter + learn/Env (simulator). Nonogram: learn/nono/* |

Adding another game = a GameAdapter (features + actions), optionally an Env simulator, and an Android
observer/executor like NonoAiMode. Agent, storage, training and the AI Lab UI are reused as-is.

## Learning method
Linear Q-learning over 27 hand-designed features per (state, action), with experience replay and a
small discount (γ = 0.1). Nonogram moves give an immediate, unambiguous result, so a big network would
add nothing but data hunger. Features are things a person looks at on ONE line: block overlap, clue
density, completion, single-line logic ("this row alone says fill / leave empty"). Combining rows and
columns, and how much to trust each signal, is learned from reward. The exact whole-grid solver is
never used by the policy — only (a) to check that simulator puzzles have one solution and (b) for the
optional real-game safety net, which blocks provably wrong moves and is counted separately.

## Measured (desktop, same code)
| | 5×5 | 10×10 | 15×15 |
|---|---|---|---|
| Untrained (all weights 0, greedy) | 26% win | 0% | 0% |
| After 3000 simulated training puzzles | 100% (0.04 mistakes) | 100% (0.07) | 96% (0.20) |
Training speed ≈ 6,000 steps/s on a desktop including puzzle generation; the phone shows its own
rate in the AI Lab. On the real Level 8 screenshot (4 frozen clues) the trained policy filled 36 of 39
provable cells with 0 wrong, then paused because the remaining cells depend on hidden clues.

## Modes
- Training: ε decays 0.30 → 0.03 over 400 episodes; every transition updates the model; autosave.
- Evaluation: ε = 0, no updates; separate statistics.
- Play: saved model, ε = 0, no updates; pauses when its best move has a negative value.

## Safety and limits
- Acts only while Running; stops on Pause/STOP, capture stopped, game not in front, cancelled tap,
  tap with no visible effect, or uncertain observation (after retries; screenshot saved).
- Waits for two identical readings before scoring a move (animations).
- Screenshots don't reveal hidden state: frozen clues are treated as unknown, never guessed.
- Real-game training costs hearts when it errs; the simulator is the intended training ground.
- Desktop training: `tools/learn/TrainNonogram.java <episodes> <dir>` writes model_vN.json; Import it in the AI Lab.
