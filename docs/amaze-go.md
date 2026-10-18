# Amaze GO mode (v0.9)

Measured from a screen recording (Levels 2-5) on a 1080x2520 phone.

- It's an arrows puzzle: each arrow is a brown (113,79,47) polyline on a square lattice of points,
  ending in an arrowhead. Tapping an arrow slides it out of the board in its head's direction; it can
  only leave if no other arrow is on the straight line ahead of the head. A blocked tap costs a blue
  water drop (3 per level). Clearing all arrows wins.
- Lattice pitch 67 px on small levels, ~17 px on the big Hard board zoomed out; lines ~0.2 pitch thick.
  Arrowheads poke ~0.27 pitch past the head point; tails end in a round cap (~0.1 pitch).
- Big boards start zoomed in ("Pinch to zoom"); the bot pinches out until no line touches the screen edge.
- Level end: "Flawless!" card, then a Next Level pill (brown (190,116,55), purple (133,73,170) before a
  Hard level). Sometimes a Daily Streak screen first: tap the grey sun, then Continue.

Reader: tools/amaze/AmazeProbe.java (desktop). Tested on every frame of the recording: Levels 2, 3, 4
and the 54-arrow Hard board read correctly; mid-animation frames are rejected (the bot waits for two
identical reads). Solver: any arrow with a clear line out is always safe and removing arrows never
blocks others, so the board always clears. Simulated on the recorded boards with batches of 1/4/10:
all cleared, zero blocked taps.
