# cartridj roadmap

What the toolkit needs next, roughly in order. Nothing here is promised: it's
a map for deciding what to do next, written while planning The Legend of PoCo
(see `games/poco/ROADMAP.md` for what the game needs from each step).

Where things stand: `cartridj.runtime` (a state machine over a frame loop,
screens, tables, a video-write queue, probes against the background's block
kinds), `cartridj.platformer` (falling, landing, ladders), `cartridj.sound`
(effects, and a table of notes on the triangle channel), NROM cartridges of
16 or 32 KB, and an importer for sprites drawn as PNGs.

## 1. Assets as data

Today art is scattered: ASCII drawings in `art.cljc` and `items.cljc`, poses
in code, sprites in an importer's generated EDN. The aim is one data format
for all of it, read by one generic reader.

An asset is a map. Something like:

```clojure
{:id      :stick
 :size    [16 16]                       ; multiples of 8
 :palette :wood                         ; a name from palettes.edn, or [colors]
 :pixels  ["................"           ; . is see-through, 1 2 3 are the palette's colors
           ".............123" ...]}
```

- **Pixels come from one of two places:** inline strings (hand-drawn, as now),
  or `:source {:png "players.png" :cell [col row]}` (cut from a sheet). The
  importers write the second kind, so hand-made and imported art are the same
  thing to everything downstream.
- **Palettes get names** (`:wood`, `:grass`) in a file of their own, and assets
  refer to them. Then "the stick's colors are the floor's" is something a test
  can check, rather than a coincidence.
- **Metatiles, poses and kinds are data too:** `{:grass {:art :grass :palette
  0 :kind :empty}}`, and poses as `{:right {:stand :players-04 :walk [...]}
  :left {:mirror :right}}`. Mirroring is a property of a pose or a tile
  (`:flip [:h]`), not code.
- **Derived art is declared:** "this drawing over that one" (`:over :floor`,
  as for the stick lying on the floor and the sign in the grass) and "the
  font on a solid background" are named operations the reader knows, not
  functions each game writes.
- **A reader,** `cartridj.assets`, with errors that say which file, which
  asset and what's wrong (a digit the palette doesn't have, a drawing that
  isn't a multiple of 8, a name that's used twice). It must work from both
  Clojure and ClojureScript. In ClojureScript the files are read when
  compiled (see `poco.util/inline-edn-resource`): check whether editing one
  makes shadow-cljs recompile, and fix that if not, so edit-and-see keeps
  working.

Order of work: items first (the simplest: one drawing and a palette; **done**: `cartridj.assets`, and poco's `assets/items.edn` and `palettes.edn`), then
background tiles and metatiles (**done**: `assets/tiles.edn`, with `:over` for derived art), then sprites and poses (the cursor and the text box font are still made in code). Each step moves one
kind of asset out of Clojure source, and the game builds the same ROM before
and after (compare the bytes).

## 2. The asset pipeline

- **Tilesheet importer** (**done**: `cartridj.sheets`; poco's `clojure -T:build
  tiles`): a PNG with a grid of cells, each drawing's `:source` naming its cell.
  Draw tiles in a pixel editor instead of ASCII. Still to do: items, from
  sheets too.
- **`.aseprite` files read directly** (LibreSprite writes them too): frames
  are poses, tags are animations, so there's no export step to forget. The
  file format is documented; parsing it takes a chunk reader and zlib for the
  cels.
- **A watcher** that re-imports when a source file changes and reloads the game.
  Pair it with hot-swapping (below) and drawing a tile shows up in the running
  game.
- **Crossing Windows and WSL** (**done** as a script: poco's `bin/fetch-art`): the editor runs on Windows, the code on WSL.
  WSL reaches Windows files easily (`/mnt/c/...`), and the other way is
  awkward, so the simple way is a script that copies the source files from a
  Windows folder into the repo, run by the watcher or by hand.

## 3. Hot-swapping code, keeping the game's state

`(play! (build))` starts the game afresh. Instead:

1. Build the new ROM and read its `.debug.edn`.
2. At a frame boundary, copy every variable's value from the old build's
   address to the new build's, matched by name (a variable whose type has
   changed gets its default).
3. Load the new ROM without resetting the machine, and start it at a second
   entry point that skips the reset code (which clears RAM) and goes to the
   main loop.
4. Restart the current state (`rt/restart`), so it draws itself from the new
   data. States are matched by name too.

Needs: a `reload` entry point in the runtime, and a `swap-rom!` in nes-cljs
that keeps RAM. Check that nothing in RAM points into ROM across frames (the
runtime's pointers are set up when a state is entered, so the restart should
cover it).

## 4. Smaller code

Poco fills NROM's 32 KB, mostly because `draw-player` writes out every
picture for every direction. Before reaching for a mapper:

- drawing a picture from a table (piece lists in ROM, one routine to draw
  them) instead of code for each;
- check what else is unrolled.

## 5. Mappers

Adopt one when PRG space (after step 4), or the need for different tiles in
different modes of a game, forces it, not before.

- A `cartridj.mapper` interface: games say which data goes in a switchable
  bank, and never touch registers. Banks are 8 KB, the unit MMC3 uses (MMC1
  can do the same with two).
- **MMC1 first** (nes-cljs has it). Work: a multi-bank ROM layout with the
  runtime and the vectors in the fixed bank; the bank-select routine (five
  serial writes); a trampoline for calling code in another bank; an assembler
  that gives each bank its own addresses; debug info that knows about banks.
- **MMC3 later**, if the IRQ is wanted (split screens, parallax). With the
  interface above, games don't change: the header, the reset code, the
  bank-select routines and the layout do. Its scanline counter is the hard
  part of emulating it. A status bar at the top of the screen doesn't need it:
  sprite-0 hit does that on any mapper.
- **CHR-RAM** is the alternative to CHR banks: upload a mode's tiles when
  entering it (the screen is blanked then anyway). Check that nes-cljs's MMC1
  covers a board with CHR-RAM.

## 6. Modes of a game

A game that's an overworld and also a platformer: a *mode* is a set of states
with its own update code, and a door (see `:door` in poco's `world.cljc`)
that says which to enter. `cartridj.screen/kinds` is shared already
(`:solid`, `:one-way`, `:ladder`), and `cartridj.platformer` has the physics.
Open questions: how the two modes share the player's variables (the same x
and y mean different things), and scrolling for the platformer's long levels.

## 7. Sound

`cartridj.sound` plays effects and a table of triangle notes. A real music
engine is a driver and a data format: FamiTone2 or the FamiStudio driver are
the ones to learn from (check their licenses), fed by a tracker. Port the
parts we need into generators rather than adopting an assembler's syntax:
neslib, which is written for cc65, mostly duplicates what the runtime does
already, and its music part is FamiTone.

## 8. Saves

Battery-backed RAM at $6000 (MMC1 boards have it): the game's variables, by
name, written out when they change. The by-name machinery from hot-swapping
is most of the work.
