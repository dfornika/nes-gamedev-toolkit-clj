# The Legend of PoCo

A little top-down adventure, made by changing text and pictures and seeing
what happens.

## The loop

Start the live environment (once), and open the page it serves:

```bash
cd games && npm run dev      # then http://localhost:9500
```

In the REPL, in `cartridj.repl`:

```clojure
(play! (poco.core/build))    ; build the game and run it in the emulator
```

Change something, save, run that line again: a new game, in under a second.

## What to change

| File | What's in it |
|------|--------------|
| `src/cljc/poco/world.cljc` | **The rooms**, drawn with letters (`T` tree, `~` water, `=` path, `#` brick, `d` door, `_` wooden floor). Add a room, add a door to it. |
| `src/cljc/poco/art.cljc` | **How things look**: the background tiles (drawn with `. 1 2 3`), the colors, and which picture is which step of the walk (`poses`). |
| `src/cljc/poco/items.cljc` | **The items**: names, what they say, their pictures (drawn with `. 1 2 3` for now) and which the player starts with. |
| `src/cljc/poco/menu.cljc` | **The items screen's layout** (slots, where the words go). |
| `src/cljc/poco/core.cljc` | **The rules**: walking speed (`walk-speed`), how fast the legs move (`frames-per-step`), the player's feet. |
| `resources/assets/sprites/*.png` | **The player**, drawn in Aseprite (16x32, one file per picture). |

After changing the PNGs, turn them into data again:

```bash
cd games/poco && clojure -T:build import
```

(It says exactly which 8x8 tile uses too many colors, if one does: a sprite
tile can have three colors plus see-through.)

## Drawing the player

Name each picture `players-NN.png`. `poco.art/poses` says which is which:
for each direction (`:down :up :left :right`) a `:stand` picture and a
`:walk` cycle of four. `(poco.art/missing)` lists the ones named but not
imported yet; they're drawn as the first picture that *is* imported, so the
game always works. Left is drawn as right, mirrored (`:flip? true`), so
side-on art only needs to be made facing right.

## In the game

D-pad: walk. A / B: change the backdrop color. Start: the items screen (D-pad moves the cursor; Start or B goes back). Select: back to the title.
Walk through a gap in the trees into the next room, or into the brick house's door to go inside (and out again through the door at the bottom). Walking diagonally is no faster than straight: both directions are slowed to 0.7071 (1 / the square root of 2) of the speed.

## Looking inside

The emulator's tabs show the game's variables (`room`, `facing`, `pose`,
`player`), and the **Feet** overlay draws the box that bumps into things.
Try changing `player/x` in the Lens while it runs.

## Tests

`npm run test:node` (from `games/`) plays the game on a simulated CPU: it
walks the player round and checks the walls, the walk and the doors.
