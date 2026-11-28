# The Legend of PoCo: roadmap

Where we're taking the game, roughly in order. The long view: a stripped-down
Stardew Valley / Zelda in Port Coquitlam, with friends and family as
characters, teenagers as the enemies, and rooms that switch to a platformer
(and perhaps other genres). Small steps, each one playable.

Done: walking (with a walk cycle and facing), four rooms, a house you can go
inside, the items screen, an item to pick up (the stick), text boxes and a
sign, and swinging the stick (B). The toolkit side of the plan is in `libs/cartridj/ROADMAP.md`.

## Now: the neighborhood

Make the rooms look like home. This is the author's part of the work, and the
more of it that's drawn in a pixel editor rather than as ASCII, the better:
see "Assets as data" and "The asset pipeline" in the toolkit's roadmap.

- Tiles: the real streets, houses, trees, fences (more than four palettes of
  color per room is the limit: look at `art/palettes`).
- Rooms: `world.cljc` is the map. Bigger is just more rooms.
- Per-room palettes are not possible yet: all rooms share four background
  palettes. Worth adding if the neighborhood needs it (a night room, an indoor
  room that isn't brown).

## Next: things to do

Each of these uses what's there:

1. **NPCs to talk to.** Standing characters (sprites, drawn over the room)
   that say something when faced and A is pressed: the text box and the
   `:sign` machinery, with the character's position kept in the room's data.
2. **Using an item.** B swings the stick already (the swing is two pictures per
   direction: `poses.edn`), but it doesn't hit anything yet: that wants
   something to hit, and a probe in front of the player, like reading a sign.
   Then the items screen gets an "equipped" marker, and the bat does the same. The pizza restores a heart; the firecracker
   goes bang.
3. **Hearts.** A health variable, shown at the top of the screen, and a way to
   take damage. This needs a status bar: sprites for the hearts to start with.
4. **Teenagers.** An enemy that wanders, and chases when the player is close.
   Objects (`cartridj.objects`) are what to build on.
5. **Pickups beyond the stick.** The mechanism is there (`:pickup` in a
   room). Items need colors that suit where they lie (see `art/stick-on-floor`,
   which only works because the stick's colors are the floor's), or to be
   drawn as sprites.

Before these grow the ROM further, draw the player from a table of pieces
instead of code for each picture: poco already fills 32 KB.

## Then: a platformer room

1. A door that leads to a side-on room (a cave, the river's bridge), using
   `cartridj.platformer` and the block kinds `:solid`, `:one-way` and
   `:ladder`. At first in the same ROM, sharing tiles with the overworld:
   learn what a mode switch needs.
2. The player's side-on art exists already (right-facing, mirrored for left);
   jumping needs a pose or two.
3. Then the real thing: its own tiles, and scrolling, once the toolkit has a
   mapper (see its roadmap).

## Later

- Music, and more than the one sound effect.
- Saving the game (the items and where you are).
- More rooms than fit in one ROM bank, once there's a mapper.
- Other genres, and references to other games.

## Ideas parked

- A map screen, showing the rooms the player has been in.
- Day and night, by palette.
- Friends and family as playable characters: the player's art as data
  (see "Assets as data" in the toolkit's roadmap) makes a second one cheap.
