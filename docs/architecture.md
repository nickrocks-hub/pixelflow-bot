# AI MINECRAFT BOT (v1.0)

A separate app from AI GAME BOT (application id `com.nickrocks.aiminecraftbot`, installs alongside it). It only plays
Minecraft. App screen: `MainActivity` (tap service, screen capture, open Minecraft). Floating panel: `TapService` hosting
`MinecraftMode`. Shared building blocks kept from AI GAME BOT: `CaptureService` (screen capture, follows rotation),
`GestureExecutor` + `core/GameAction`, `Recorder` (logs), `Ui`, `vision/Frame`. No libraries, no internet permission.

## Minecraft first-minutes player (com.mojang.minecraftpe)

Goal (from the user's screen recording): trees → logs → planks → crafting table → sticks → place the table → wooden
pickaxe (+ axe and sword when there's wood) → pickaxe in hand. Not the whole game: mining, the Nether, strongholds and
the Ender Dragon are out of reach for screen-capture play (seconds per decision, ~50 ms+ input lag, no game state).

- `core/mc/McLayout`: every HUD / crafting-screen position, measured on the user's 2520×1080 landscape screen
  (default GUI scale, touch controls), scaled to the captured frame.
- `core/mc/McVision`: screen kind (world HUD = hearts + hotbar "…" dots; crafting = light panel + dark recipe book;
  3×3 = grid border at x≈1599), health in half hearts, tree trunks (bark-coloured 12-px cells, dirt excluded, vertical
  gaps from vines closed, tall connected regions; a crafting table's planks top rejects it), press point on the trunk
  nearest eye level, change detection patches, camera-turn measurement (column-profile cross-correlation).
- `core/mc/McIcons` + generated `McIconData`: 12×12 foreground descriptors (background = slot ring colour, so grey,
  red and lilac cells all work) matched to 46 templates cut from the recording (`tools/mc/MakeIcons`); the stack count
  read from Minecraft's 5×7 font. Recipe numbers are the game's own count of what the inventory can make:
  planks ÷ 4 = logs, button = planks.
- `core/mc/McBrain`: calibrate turn → gather (walk to the nearest trunk, hold 3.6 s on it, check the block changed,
  walk in to pick it up; check the inventory every 2 logs) → craft in the inventory (planks until no logs, table,
  1–2 stick batches) → place + open the table (tries 5 spots) → pickaxe, axe, sword → hold the pickaxe.
  Stops on 1.5 hearts of damage, 4 unknown screens in a row, 900 steps, or repeated failed crafts.
- `MinecraftMode`: panel (▶ Start, ⏸, ■, 🕹 Joystick / ✚ D-pad, tool set, 🔍 Look, 📸); hides the panel during
  touches; walks with a continued stroke (joystick pushed up and held) or a long press on the D-pad arrow; logs every
  step to Download/AIGameBot and saves the screen when it stops on a problem.
- `CaptureService` now follows rotation: when the display's shape changes it resizes the virtual display and swaps
  the ImageReader, so a landscape game isn't squeezed into the portrait capture started from the app.

Verified (`tools/mc/McTests`, 57 checks on 20 frames of the recording): screen kinds, health, the crafting reader
(every recipe, counts, hotbar, output on 5 screens), trunks close/far/absent, table not a tree, turn measurement on
shifted frames, and the planner walked through the recorded sequence to "Done: wooden pickaxe".
Not verified: anything on the phone — gestures in Minecraft, joystick/D-pad walking, mining reach, picking up logs,
placing the table. Birch (v1.2): pale bluish-grey bark with black streaks, upright, brighter than its sides — checked on the user's dusk birch-forest screenshot. Oak/spruce probably found by the brown-bark rule.

```
S=app/src/main/java/com/nickrocks/pixelflowbot
javac -d /tmp/mc $S/vision/Frame.java $S/core/mc/*.java tools/mc/*.java
java -cp /tmp/mc McTests tools/mc/samples
java -cp /tmp/mc WorldProbe /tmp/out tools/mc/samples/*.jpg     # annotated trunks / crafting reads
java -cp /tmp/mc MakeIcons tools/mc/samples $S/core/mc/McIconData.java   # rebuild templates
```

