# Nonogram.com mode (v0.7)

Measured from a screen recording on a 1080x2520 phone.

- Grid: black outer border + black line every 5 cells; thin grey (lum ~200) 1px lines between cells.
  Cell pitch is measured from line *centres* (thick lines are drawn over cell edges).
- Filled cell (50,70,96); crosses are thin strokes of the same navy; mistakes are red.
- Clues: column numbers stacked above the grid, row numbers in a line to its left, in rounded
  lavender (233,236,247) tabs. Digits are dark indigo (39,33,95), or grey (~160,170,185) once the
  line is satisfied (the tab can also turn white).
- The digit face matches Inter; templates = real glyphs + Inter renders (covers 0 and 8).
  Real digits match within ~0.03; tutorial art scores 0.15+, so reads above 0.08 are rejected.
- Fill/cross switch under the grid: the square icon is navy when fill is on, grey-blue when not.

Bot flow: read (panel hidden) → check sums/fit → solve with exact line solving + search, proving
the answer is unique → refuse if the board disagrees → make sure fill mode is on → swipe/tap runs
row by row → re-read every few moves; stop on any wrong fill or red mark.

Desktop checks: `tools/nono/NonoProbe.java` (read screenshots), `MakeTemplates.java`, `FontTemplates.java`.

## Auto next level (v0.8)
- Nonogram "Level Completed!" screen: white pill (y 2154-2282, x ~200-880 on 1080x2520) on bright blue
  (20,130,232). Popups (Rate Game, tutorial note) dim the background, so the detector (nono/NextButton) ignores it.
- Pixel Flow / Candy Crush: user-taught steps (AutoNext.java). Each step = tap point + 18x6 colour
  fingerprint of a 36%x6% patch around it; tapped only after matching on two looks in a row
  (mean diff < 16, 90th percentile < 40). Tested on the Nonogram recording: a fingerprint taught on one
  Level Completed frame matched all 9 clean ones and none of the popups or other screens.

## Frozen clues (v0.9.2)
- Some levels hide clues under ice: the tab turns ice-blue (219-231, 247-249, 251) with a cyan snowflake
  and no number (seen on Level 8: columns 3 and 7, rows 8 and 10). The snowflake used to be misread as
  an 8 or 7.
- The reader now marks those lines as unknown; the solver fills only cells that are the same in every
  picture that fits the visible clues (line logic + what-if probing), then re-reads in case the ice melted.
  On Level 8 that's 84 of 100 cells; the other 16 depend on the hidden clues.
