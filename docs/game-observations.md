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

## Board reader status (Python prototype)
- tools/board_reader.py: calibrates grid once per level from the full board (frequency fit of cube spacing), then reads every cube color.
- Tested on Level 24 start, Level 24 mid-level (with cleared cubes) and Level 25 start: redrawn boards match the screenshots.
- Known issue: 2 false "black" cells on a mid-level Level 24 frame (likely a character's shadow).
