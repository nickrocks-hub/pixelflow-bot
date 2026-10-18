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
