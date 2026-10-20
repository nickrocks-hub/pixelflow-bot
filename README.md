# AI GAME MINESWEEPER

An Android app that plays classic-looking Minesweeper by itself, as fast as it can, entirely on the
phone (no internet, no cloud AI).

## How it plays

1. **Sees the screen** — screen capture at 540 px wide (`CaptureService`).
2. **Finds the board** (`core/MineVision`) — raised tiles start with a white bevel on their left and top,
   so the white edges line up in an even comb; the comb gives the grid (any size). It reads every tile:
   closed, flagged, opened 0–8 (by the classic number colours), mine, hit mine. It also reads the red
   7-segment mine counter and finds the smiley.
3. **Waits for a settled picture** (`core/MineBrain`) — every tapped tile has opened, no `0` is still next
   to a closed tile (a half-drawn opening or a tile drawn pressed-in), every number fits, and the same
   reading came twice or the screen has gone still.
4. **Solves exactly** (`core/MineSolver`) — splits the closed tiles next to numbers into connected regions,
   counts every mine arrangement that fits (with the mine total), and gets each tile's exact chance of a
   mine. Every tile at 0% is tapped in one burst. Only when none is certain does it guess — the lowest
   risk tile.
5. **Taps** (`TapService`, an accessibility service) — short taps sent back to back in one gesture
   (45 ms apart by default). It never places flags. After a loss it taps the smiley and plays again.

## Desktop checks (`tools/`)

```
javac -d out app/src/main/java/com/aigame/minesweeper/core/*.java tools/*.java
java -cp out ReadShot tools/samples/new_game_9x9.jpg 540   # read a real screenshot
java -cp out Sim reader 300                                  # random mid-game boards, every tile compared
java -cp out Sim logic 20000 9 9 10                          # solver win rate
java -cp out Sim pixels 100 9 9 12                           # rendered screens → reader → brain → taps
```

## Use

1. Install the APK from the latest *AI GAME MINESWEEPER build* release on this repo.
2. Open the app → **Open Accessibility settings** → turn on *AI GAME MINESWEEPER*
   (if blocked as a restricted setting: App info → ⋮ → Allow restricted settings).
3. **Start bot**, share the entire screen, open your Minesweeper game on a new board.
4. The floating START/STOP button pauses and resumes; it shows the time of each win.
