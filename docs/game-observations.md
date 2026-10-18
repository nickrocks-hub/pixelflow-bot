# Pixel Flow: observed facts

Source: user's recordings of Level 24 (full win) and Level 25 (start), Oct 2026.
"Confirmed" = stated by the user or seen clearly in a recording. "Hypothesis" = still to verify.

## Screen layout (1080x2520 phone)
- Top: Pause, "Level N", coin count with a "+" (shop: never tap).
- Board: pixel-art grid of cubes inside a frame. Level 24 = 31x32 (full board), Level 25 = 35x41. Size varies per level.
- Conveyor belt loops around the board. Counter "x/5" at bottom-left of the belt.
- 5 waiting slots under the belt.
- Character queue: 4 columns. Only the front character of each column is tappable (ones behind are faded).
- Bottom bar: 4 boosters (some marked "+", i.e. cost coins). The bot never touches these.

## Colors seen
Cubes: white, yellow, orange, red, blue, green, purple, black.
Characters: same colors incl. black (Level 25). Shot counts seen: 1 to 50.

## Rules
- Confirmed: tapping a character sends it onto the belt; it shoots cubes of its own color on the board edge facing it, and its number counts down.
- Confirmed (user): belt holds up to 5 characters.
- Confirmed (user + Level 25 recording): you lose when all 5 waiting slots are full.
- Confirmed (user): a character in a waiting slot can be tapped to send it around again.
- Confirmed (recording): level is won when the board is cleared, even with characters still in waiting slots.
- Hypothesis: "x/5" shows free belt spaces (it reads 5/5 with an empty belt and drops as characters board). User described it as the count on the board; verify.
- Hypothesis: a character that completes a lap with shots left goes to a waiting slot.
- Hypothesis: a character only hits the outermost exposed cube of its color in its line of fire.

## End screens and hazards (bot must stop, never tap)
- Win: "LEVEL N COMPLETED!" with green "Continue" and an ad button "80 (video icon)".
- Map screen: "Play", "ADS / NO ADS" purchase button, coin "+".
- Loading screen: "PIXEL FLOW / Loading".
- Fail, step 1 (confirmed, Level 25): when all 5 waiting slots fill, their outlines turn red and an "OUT OF SPACE!" popup appears:
  "900 (coins)" continue button, a "Fail Offer" real-money purchase (CAD 8.29), a red X to close, and "Hold to See the Game".
- Fail, step 2: after X, "Level N / You Failed" with an infinite-lives heart and a "Try Again" button. Then Loading, and the level restarts.
- Bot policy: stop on any popup. Never tap coin, purchase or ad buttons. Auto-retry (X then Try Again) only if the user enables it later.

## Vision status (v0.2, Java in the app)
- app/.../vision: board grid + 8 colours, waiting slots, queue front row (colour + shot count), x/5 counter, screen type (gameplay / popup / other).
- Verified on all 344 frames of 3 recordings via tools/desktop (same code as the app): characters and numbers correct in every spot-check; popups and map detected.
- Board calibration waits for a still board and an empty belt. Limitation: if reading starts mid-level, the grid can be wrong until the next level or a retry.

## Auto-play status (v0.3)
- Start/Pause/STOP on the floating panel (top of screen). Taps only queue (4) and waiting-slot (5) characters.
- Planner v1 (rule, not learned): send the character whose colour has the most "exposed" edge cubes (first cube in a row/column from each side); slots preferred; needs >= 2 free belt spaces; digs only with <= 2 slots used; otherwise pauses as stuck.
- Safety: game app must stay in front; stops at any popup/win/fail/map; pauses on unreadable state, no-effect taps, 45 s idle; max 400 moves.
- Log: Download/PixelFlowBot/pfbot_*.txt (JSON lines: moves with state, level_end outcome guess).
- Replayed on recordings (decisions only, no game simulation). Not yet tested on the phone.

## Game log 1 (v0.3, Level 25, lost after 16 moves)
- App package: com.loomgames.pixelflow.
- Grid misread (35x38 -> 33x36; true 35x41) because the box-based fit drifted mid-level.
- Several same-colour characters sent together; leftovers (K1, K1, K2, B9, Y8) filled the slots.
- Fixed in v0.4: grid from cube spacing (same on every recorded frame, any point in a level), cubes must be seen in 2 reads (filters shots), planner v2 (in-flight shots per colour, slots first, clean moves preferred, calculated risk when no safe move, as the user did in Level 24).

## Game 2 (v0.4, Level 25, WON) - logs/pfbot_20261018_075411_v04_L25_win.txt
- Same level that v0.3 lost. 61 moves in about 100 s; never more than 2 waiting slots used; ended on the win popup with the board cleared.
- Grid read the full 35x41 board correctly from the first frame.
- Noise to fix later: up to ~37 cells read as orange mid-game on a level with no orange cubes (likely hit animations on yellow cubes).
- Win rate so far (v0.4): 1 of 1. Need more games before trusting it.

## Level 26 (v0.4 could not play) - samples/level26_start.jpg
- Queue has 3 columns here (centred at x 336/540/745), not 4. Column count varies by level.
- "?" hidden characters exist further back in the queue.
- New cube/character colour: dark green (s 1.0, v 0.54-0.64), distinct from bright green.
- Board is taller: rows from y 385 to 1350. The panel frame itself is identical on L24-26 (inner edges x 93-987, y 387-1398).
- Fixed in v0.5: queue row scanned across the full width; "?" recognised; DARK_GREEN colour; reading area = whole panel inside, minus rounded corners; stray shine on white characters ignored.

## Game 3 (v0.5, Level 26, WON) - logs/pfbot_20261018_081719_v05_L26_win.txt
- 66 moves, about 160 s, ended on the win popup with the board cleared.
- Problem: slot characters read as "K0"/"W0" (impossible: a digit was missed). The bot re-sent slot 2 about 15 times; black and white cube counts didn't change during that time.
- Problem: shaded bright-green bottom row read as dark green (s 0.86-0.88, v 0.73-0.82; true dark green s 0.99, v 0.55-0.64).
- Fixed in v0.6: 0-shot readings occupy a slot but are never tapped; slot characters that cleared nothing aren't re-sent until their colour's cubes drop; dark green needs s > 0.93 and v < 0.75.
- Win rate so far: v0.4 1/1 (L25), v0.5 1/1 (L26). Total auto-play: 2 wins, 1 loss (v0.3).

## Board reader status (Python prototype)
- tools/board_reader.py: calibrates grid once per level from the full board (frequency fit of cube spacing), then reads every cube color.
- Tested on Level 24 start, Level 24 mid-level (with cleared cubes) and Level 25 start: redrawn boards match the screenshots.
- Known issue: 2 false "black" cells on a mid-level Level 24 frame (likely a character's shadow).
