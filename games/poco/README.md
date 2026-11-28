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
| `src/cljc/poco/world.cljc` | **The rooms**, drawn with letters (`T` tree, `~` water, `=` path, `#` brick, `d` door, `_` wooden floor, `s` a stick to pick up, `S` a sign). Add a room, add a door to it. |
| `resources/assets/tiles.edn` | **The background's tiles**: each a cell of a tilesheet (`tiles/outdoors.png`) or a drawing in text (`. 1 2 3`), its palette by name, and what it's like to walk into (`:kind`). `:over` draws one on another (the stick on the floor). The colors are in `palettes.edn`. See "Drawing the background". |
| `src/cljc/poco/art.cljc` | **How things look**: reads those files, and puts the pictures and colors together; which picture is which step of the walk (`poses`, from `poses.edn`). |
| `resources/assets/items.edn`, `palettes.edn` | **The items**, as data: each one's drawing (text, a letter a pixel), palette (by name), words, and whether you start with it. `src/cljc/poco/items.cljc` reads and checks them (`cartridj.assets`). |
| `src/cljc/poco/menu.cljc` | **The items screen's layout** (slots, where the words go). |
| `src/cljc/poco/talk.cljc` | **What the game says** in its text box: finding an item, and signs (a room's `:sign` in `world.cljc`). |
| `src/cljc/poco/core.cljc` | **The rules**: walking speed (`walk-speed`), how fast the legs move (`frames-per-step`), the player's feet. |
| `resources/assets/sprites/{player,enemies}/*.png` | **The player and the enemies**, drawn in Aseprite (16x32, one file per picture; `player.ase`, `teenager-01.ase`... are the Aseprite files they come from). They share the four sprite palettes, so they're imported together. The NPCs and the items are drawn in `sprites/npcs` and `sprites/items`, and aren't in the game yet. | |

After changing the PNGs, turn them into data again:

```bash
cd games/poco && clojure -T:build import
```

(It says exactly which 8x8 tile uses too many colors, if one does: a sprite
tile can have three colors plus see-through.)

## Drawing the background

The background's tiles are cells of a tilesheet, `resources/assets/tiles/outdoors.png`:
16x16 pixels each, eight to a row, in the order of `tiles.edn` (each tile's
`:source` says which cell). Draw them in a pixel editor, then:

```bash
clojure -T:build tiles
```

which reads the cells into `sheets.edn` (that's what the game builds from). Rules:

- transparent pixels show the backdrop color (the grass's green);
- every other pixel has to be exactly one of the tile's palette's three colors
  (in `palettes.edn`; the NES palette of Aseprite and LibreSprite has them),
  or `clojure -T:build tiles :nearest? true` takes the closest of the three;
- a new tile is a new entry in `tiles.edn` with a `:source` cell (add the
  cell to the sheet, and its colors to a palette if need be).

Mistakes are listed with the tile, the cell and the pixel.

**Where the art lives** (the Windows folder has the same layout, so copying
is one-to-one):

```
resources/assets/
  tiles/outdoors.png            the background's tilesheet (tiles.edn says which cell is which)
  sprites/player/player-NN.png  the player's pictures, and player.ase they come from
  sprites/enemies/teenager-NN.png  the enemies' (poses.edn: how each is drawn)
  sprites/npcs/*.ase            the other characters (not in the game yet)
  sprites/items/items-01.ase    the items' art (not in the game yet)
  *.edn                         data: items, palettes, tiles; generated: sheets.edn, sprites/sprites.edn
```

**Windows to WSL:** if the editor is on Windows, `bin/fetch-art` copies the
pictures between the Windows folder (the one that has `tiles/` and `sprites/`
in it) and `resources/assets` (only newer files,
only `.png`, `.ase` and `.aseprite`). Once:

```bash
bin/fetch-art --push /mnt/c/Users/you/art/poco/resources/assets   # the repo's pictures -> Windows, to edit
```

and after drawing (`--watch` keeps copying while you work):

```bash
bin/fetch-art /mnt/c/Users/you/art/poco/resources/assets && clojure -T:build tiles
```

(Set `POCO_ART_DIR` to leave the folder out.)

## Drawing the player

Name each picture `player-NN.png`. `sprites/player/poses.edn` says which is
which: for each direction (`:down :up :left :right`) a `:stand` picture and a
`:walk` cycle of four and a `:swing` of two (08 and 09 facing us, 10 and 11
facing away, 12 and 13 facing right: B swings the stick). `(poco.art/missing)` lists the ones named but not
imported yet; they're drawn as that direction's standing picture, so the
game always works. Left is drawn as right, mirrored (`:left {:mirror :right}`), so
side-on art only needs to be made facing right.

**Colors of the sprites:** the NES has four sprite palettes of three colors,
and every 8x8 tile of a picture has to fit in one of them (the importer says
which tile doesn't). The player's pictures get *shared* palettes, which are
always loaded (three right now: the fewer the player takes, the more is left
for everyone else). Every other character (the teenager, the NPCs) brings
*guest* palettes, only for the tiles that don't fit the shared ones, and a
room that has that character in it loads them (`:npcs` and `:enemies` in
`world.cljc`) into the slots after the shared ones. So each room can have one
guest's colors, not two: the importer says so if a character needs more than
there's room for, and the game won't build with two guests in a room. A
character that only uses the player's colors costs nothing. Right now: the
player has brown/blue/white, dark red/blue/skin and dark red/brown/skin; the
teenager brings black/green/sickly skin.

## In the game

D-pad: walk. A: read a sign or talk to someone (face them, close up; A or B closes the text). People and the teenager can't be walked through. B: swing the stick (once you've picked it up): it hits what's just in front of you, and the teenager goes after three hits. Start: the items screen (D-pad moves the cursor; Start or B goes back). Select: back to the title.
Walk through a gap in the trees into the next room, or into the brick house's door to go inside (and out again through the door at the bottom). There's a stick lying on the floor inside: walk over it to pick it up (then look in the items screen). You start without it; `starting` in `poco.items` says what you begin with. Walking diagonally is no faster than straight: both directions are slowed to 0.7071 (1 / the square root of 2) of the speed.

## Looking inside

The emulator's tabs show the game's variables (`room`, `facing`, `pose`,
`player`), and the **Feet** overlay draws the box that bumps into things.
Try changing `player/x` in the Lens while it runs.

## Tests

`npm run test:node` (from `games/`) plays the game on a simulated CPU: it
walks the player round and checks the walls, the walk and the doors.
