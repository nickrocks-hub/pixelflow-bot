# In-app Nonogram game (v1.1)

A playable Nonogram inside PixelFlow Bot, used as the test bed for the learning agent.
Home screen → **▦ Nonogram game → Play Nonogram**.

## Rules (game/nonogram/Clues.java, Board.java)
- Cells are **unknown**, **filled** or **marked empty** (✕). Unknown cells are plain; marked cells are darker with a ✕.
- A clue lists the lengths of consecutive filled groups, left→right (rows) or top→bottom (columns),
  separated by at least one empty cell; groups may touch the edges. "3 1" = 3 filled, ≥1 empty, 1 filled.
- A line with no filled cells has an empty clue list, shown as "0".
- Solved = the filled cells satisfy every row and column clue (checked from the clues, not from a stored answer).

## Playing
- Tools: ■ Fill, ✕ Mark empty, ○ Clear. Tap a cell, or drag along a row/column to do several in one stroke.
  Tapping a cell that already has the tool's state clears it. Drags only change unknown cells.
- Undo (a whole drag is one step), Restart, completion detection (🎉, timer, move count). Satisfied clues dim.
- Sizes 5×5, 10×10, 15×15. Sets: Practice, Training set, Evaluation set, or any custom seed.
- 💡 Hint asks the **constraint solver** and says why (line logic / contradiction test / search).

## Puzzles (PuzzleGenerator.java)
- Every puzzle is rebuilt from a seed derived from (set, size, number) → same puzzle on every phone.
- A picture is accepted only if `LineSolver.countSolutions` proves **exactly one** solution within a fixed
  search-node budget. Node counting, not a time limit, so acceptance never depends on phone speed.
- Evaluation set: 60 × 5×5, 40 × 10×10, 20 × 15×15, fixed and duplicate-free. Training uses a different seed
  salt and skips any puzzle whose clues equal an evaluation puzzle → the sets never overlap (tested).

## Agent interface
- **Observation** (Observation.java): size, row/column clues, visible cell states, the last 16 actions.
  Built from `Board`, which never holds the solution. `Puzzle`'s solution is package-private; agents live in
  another package (`game/nonogram/agent`) and can't reach it. The bench checks this by reflection.
- **Actions** (Action.java): fill / mark empty / clear a cell. Off-board coordinates → INVALID, nothing changes;
  a move that changes nothing → NO_CHANGE. The environment also flags moves that bring back a board position
  already seen this episode.
- **Training environment** (NonogramEnv.java) uses the hidden solution only to compute rewards and scores
  (an outside game like Nonogram.com may give no such feedback):
  correct fill +1, correct ✕ +1, wrong fill/✕ −5 (like losing a heart), fixing a wrong cell +0.5, clearing a right cell −1,
  no-change −0.5, repeated position −0.5, invalid −1, solved +10, step limit (3×cells) −5.

## Agents — kept separate
| Agent | What it is | Learns? |
|---|---|---|
| Random | random fill/✕ on an unknown cell | no — reference |
| **Constraint solver** (SolverPlayer) | line logic → contradiction test → search, from observation only. Also validates puzzles. | **no** — baseline |
| **Learner** (LearnerPlayer + PlayAdapter) | linear Q-learning (shared `learn/` core) over basic counts + two hand-written heuristics (overlap, unreachable cells) | yes |
| **Learner + solver hints** | same, plus the exact single-line solver's verdict as input features | yes, but **labelled solver-assisted** |

The two learners are separate models (`files/ai/nonogram-game/`, `files/ai/nonogram-game-assisted/`).
Every AI move on screen is labelled with its source; solver output is never shown as learned behaviour.
Loop guard: a fill/✕ the learner made and then cleared isn't offered again while it's in its recent actions.

## Measured (desktop, same code: `tools/learn/NonogramGameBench.java`)
Evaluation puzzles only (never trained on); learners trained 1000 × 5×5, then +1000 × 5/10, then +1000 × 5/10/15.

| Player | 5×5 solved | 10×10 solved | 15×15 solved | mistakes / 10×10 |
|---|---|---|---|---|
| Random | 0% | 0% | 0% | 50 |
| Constraint solver (baseline) | 100% | 100% | 100% | 0 |
| Learner, untrained | 0% | 0% | 0% | 76 |
| Learner, 3000 training puzzles | 98% | 73% | 45% | 4.1 |
| Learner + solver hints, untrained | 0% | 0% | 0% | 91 |
| Learner + solver hints, 3000 puzzles | 100% | 100% | 100% | 0 |

Reward choice: with ✕ worth +0.3 and mistakes −2, guessing paid off and 10×10 reached only 40–52%;
with ✕ +1 and mistakes −5 the pure learner reached 65–85% on the same budget (2 seeds each).
The solver-assisted learner reaching 100% mostly shows it learned to trust the solver's input — that's
why it's labelled and kept separate. The pure learner's numbers are what it learned on its own.
Re-run: `java -cp … NonogramGameBench <episodes> [modelDir]` (build line in the file header).

## Shared architecture
`learn/` (Agent, LinearQ, ReplayBuffer, ModelStore, SimRunner, GameAdapter, Env) is game-independent.
A new phone game needs: a rules/board model, an Observation, an Env for training, and a GameAdapter giving
candidate actions + features. Nonogram.com (screen) mode from v1.0 is unchanged.
