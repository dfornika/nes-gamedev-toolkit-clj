(ns poco.core
  "The Legend of PoCo: walk round a little world, one screen at a time.

  The D-pad walks (the player faces the way they're going, and their legs
  move while they do); walk off the edge of a room, through a gap, and you
  arrive in the next one. B swings the stick (if the player has it). A reads
  a sign (face it first). Start opens the items screen (and closes it
  again); Select goes back to the title.

  This file is the rules. How things look is in poco.art, and where they
  are in poco.world: that's what to change most. The rest is the machinery
  that makes a program of it, written in cartridj.runtime's terms: states
  (the title, and being in a room), each with the code to run when it
  starts and every frame.

  At the REPL, `(play! (poco.core/build))` builds the game and runs it:
  change something in any of the three files and run that again."
  (:require [zeropage.asm :as asm]
            [zeropage.gen :as gen]
            [zeropage.sub :as sub :refer [call]]
            [cartridj.hardware :as hw]
            [cartridj.runtime :as rt]
            [cartridj.tileset :as tileset]
            [poco.art :as art]
            [poco.items :as items]
            [poco.menu :as menu]
            [poco.talk :as talk]
            [poco.world :as world]))

;; ---------------------------------------------------------------------------
;; Numbers to tune

(def walk-speed
  "How far the player walks each frame, in pixels."
  1.5)

(def diagonal-speed
  "How far they walk along each axis when walking diagonally: the speed
  divided by the square root of two (0.7071). Going 1.5 right AND 1.5 down
  is really 2.1 pixels along the diagonal, which is why it felt fast.
  Scaling both by 0.7071 makes the diagonal as long as a straight step."
  (* walk-speed 0.7071))

(def swing-frames
  "How long a swing of the stick lasts, in frames: half of it in each of
  the swing's two pictures (see sprites/player/poses.edn)."
  12)

(def swing-reach
  "How far the stick reaches past the player's feet, in pixels: a wimpy
  weapon, so close."
  10)

(def hurt-frames
  "How long an enemy flickers after a hit."
  20)

(def enemy-walk-frames
  "How long an enemy walks one way before it thinks again: this many
  frames, plus a random number up to `enemy-walk-spread`. At a pixel a
  frame (a bit slower than the player), 24 to 87 frames is 24 to 87 pixels."
  24)

(def enemy-walk-spread
  "The most frames added to `enemy-walk-frames`, less one: a power of two,
  minus 1 (63 is 64 possible numbers)."
  63)

(def enemy-pause-odds
  "An enemy that's thought again stands still instead of walking one time
  in this many (2 or 4: a half or a quarter of the time). A pause lasts
  as long as a walk."
  4)

(def enemy-walk-every
  "An enemy moves a pixel every this many frames (a power of two: 1 is
  every frame, 2 every other). The player moves 1.5 pixels a frame."
  1)

(def frames-per-step
  "How many frames of each step of the walking, as a power of two (the
  number of times to halve the frame count): 3 is 8 frames, so a step
  takes about an eighth of a second."
  3)

(def feet
  "The player is 16x32, but only the feet bump into things: the box from
  the left, top, right and bottom of their picture."
  {:left 3 :top 26 :right 12 :bottom 31})

;; ---------------------------------------------------------------------------
;; What the game remembers

(def enemies
  "Every enemy of every room (:enemies in poco.world), each with its :room
  and a number (:index): the game keeps how many hits each has left, by
  that number."
  (vec (map-indexed (fn [i enemy] (assoc enemy :index i))
                    (for [[room {:keys [enemies]}] world/rooms
                          enemy enemies]
                      (assoc enemy :room room)))))

(def variables
  [[:room   [:enum (zipmap (range) world/room-names)] {:doc "Which room the player is in"}]
   [:player {:x :u8.8, :y :u8.8}]                      ; fixed point: it can move by parts of a pixel
   [:facing [:enum (zipmap (range) art/directions)] {:doc "The way the player faces"}]
   [:moving :bool {:doc "Walking this frame"}]
   [:speed  :u8.8 {:doc "Pixels to walk this frame: less when going diagonally"}]
   [:walk   :u8   {:doc "Counts frames of walking"}]
   [:pose   :u8   {:doc "What to draw: 0 standing, 1 to 4 the steps of the walk, 5 and 6 the swing"}]
   [:swing  :u8   {:doc "Frames left of swinging the stick (0: not swinging)"}]
   [:items  [:bits (into {} (map-indexed (fn [i {:keys [id]}] [id i]) items/items))]
    {:doc "The items the player has: a bit each, in the order of poco.items"}]
   [:message :u8 {:doc "Which of poco.talk's messages to show"}]
   [:enemy-hp [:array (max 1 (count enemies)) :u8] {:doc "Hits each enemy has left (0: gone), as numbered in `enemies`"}]
   [:enemy-hurt [:array (max 1 (count enemies)) :u8] {:doc "Frames each enemy has left of flickering after a hit (it stands stunned meanwhile)"}]
   [:enemy-x [:array (max 1 (count enemies)) :u8] {:doc "Where each enemy is: the left of its picture, in pixels"}]
   [:enemy-y [:array (max 1 (count enemies)) :u8] {:doc "Where each enemy is: the top of its picture, in pixels"}]
   [:enemy-dir [:array (max 1 (count enemies)) :u8] {:doc "The way each enemy is walking: 0 right, 1 left, 2 down, 3 up, 4 standing still"}]
   [:enemy-timer [:array (max 1 (count enemies)) :u8] {:doc "Frames until each enemy thinks again"}]
   [:enemy-flip [:array (max 1 (count enemies)) :u8] {:doc "1 if each enemy last went left, so it's drawn facing that way"}]
   [:selected :u8 {:doc "The slot the items screen's cursor is on (0 to 7)"}]
   [:redraw :bool {:doc "The words on the items screen need writing"}]])

;; ---------------------------------------------------------------------------
;; Code generators

(defn by-room
  "Run (code room-name) for the room the player is in: the code for every
  room, one after another, each one only when it's the one."
  [code]
  (let [done (asm/genlabel "roomed")]
    [[:lda :room]
     (for [[i name] (map-indexed vector world/room-names)]
       [[:cmp [:imm i]]
        (gen/when-flag :equal
                       (code name)
                       [:jmp done])])
     [:label done]]))

(defn- video-address [addr]
  [[:lda [:imm (bit-shift-right addr 8)]] [:sta :ppuaddr]
   [:lda [:imm (bit-and addr 0xFF)]] [:sta :ppuaddr]])

(defn- copy-palettes
  "Write the 16 bytes at `label` to the PPU's palette memory, from the
  `from`th byte on (PPUADDR is where it goes)."
  [label from]
  [[:ldx [:imm from]]
   (gen/do-while :not-equal
                 [:lda label :x]
                 [:sta :ppudata]
                 [:inx]
                 [:cpx [:imm 16]])])

(defn world-palettes
  "Inside draw-screen: the rooms' background palettes, and the backdrop
  color. (A function: each use needs labels of its own.)"
  []
  [(video-address 0x3F00)
   [:lda [:imm art/backdrop]]
   [:sta :ppudata]
   (copy-palettes :world-palettes 1)])

(defn item-palettes
  "Inside draw-screen: the items screen's background palettes."
  []
  [(video-address 0x3F00)
   (copy-palettes :item-palettes 0)])

(defn- item-bit
  "The mask for the bit that says the player has `item`."
  [item]
  (menu/bit-of (first (keep-indexed (fn [i {:keys [id]}] (when (= id item) i)) items/items))))

(defn- take-away-pickup
  "Inside draw-screen: if the player has already taken the item that's
  lying in this room (see :pickup in poco.world), draw the floor over it."
  [name]
  (when-let [{:keys [item at]} (:pickup (get (into {} world/rooms) name))]
    (let [[c r] at
          [top-left top-right bottom-left bottom-right]
          (tileset/block art/tiles :floor)]
      [[:lda :items]
       [:and [:imm (item-bit item)]]
       (gen/when-flag :not-zero
                      (rt/at (* 2 c) (* 2 r))
                      [:lda [:imm top-left]] [:sta :ppudata]
                      [:lda [:imm top-right]] [:sta :ppudata]
                      (rt/at (* 2 c) (inc (* 2 r)))
                      [:lda [:imm bottom-left]] [:sta :ppudata]
                      [:lda [:imm bottom-right]] [:sta :ppudata])])))

(defn- enemies-in
  "The enemies (with their numbers) in the room called `name`."
  [name]
  (filter #(= name (:room %)) enemies))

(defn- standing-in
  "The characters (:npcs and enemies) of the room called `name`."
  [name]
  (concat (:npcs (get (into {} world/rooms) name)) (enemies-in name)))

(defn- alive
  "`code` for a character, run only while it's there: people always are,
  an enemy until it's out of hits."
  [{:keys [index]} code]
  (if index
    [[:lda [:+ :enemy-hp index]]
     (gen/when-flag :not-zero code)]
    code))

(defn- guest-palettes
  "Inside draw-screen: load the sprite palettes of the guest character
  that's in this room (see cartridj.sprites): the people and enemies of the
  room need theirs, in the slots after the player's. A room can have one
  guest's palettes, not two."
  [name]
  (let [guests (distinct (mapcat #(art/guests-of (:kind %)) (standing-in name)))]
    (when (> (count guests) 1)
      (throw (ex-info (str "The room " (pr-str name) " has characters with palettes of their own: "
                           (pr-str guests) ". There's room for one guest's in a room.") {})))
    (when-let [who (first guests)]
      (for [[i palette] (map-indexed vector (art/guest-palettes who))
            :let [slot (+ art/guest-slot i)]]
        [(video-address (+ 0x3F11 (* 4 slot)))     ; the first of its three colors
         (for [color palette]
           [[:lda [:imm color]] [:sta :ppudata]])]))))

(defn draw-room
  "Draw the room the player is in (the screen goes blank while it does)."
  []
  (rt/draw-screen
   (world-palettes)
   (by-room #(rt/background %))
   (by-room guest-palettes)
   (by-room take-away-pickup)))

(def start-game
  [(gen/move :room [:imm (world/room-number (:room world/start))])
   (gen/move16 :player/x [:imm (gen/fixed (:x world/start))])
   (gen/move16 :player/y [:imm (gen/fixed (:y world/start))])
   (gen/move :facing [:imm 0])
   (gen/move :moving [:imm 0])
   (gen/move :walk [:imm 0])
   (gen/move :pose [:imm 0])
   (gen/move :swing [:imm 0])
   (for [{:keys [index hp x y]} enemies]
     [[:lda [:imm hp]] [:sta [:+ :enemy-hp index]]
      [:lda [:imm x]] [:sta [:+ :enemy-x index]]
      [:lda [:imm y]] [:sta [:+ :enemy-y index]]
      [:lda [:imm 0]] [:sta [:+ :enemy-hurt index]]
      [:sta [:+ :enemy-timer index]]
      [:sta [:+ :enemy-flip index]]
      [:lda [:imm 4]] [:sta [:+ :enemy-dir index]]])
   (gen/move :items [:imm menu/starting-bits])
   (gen/move :selected [:imm 0])])

(defn- within
  "Branch to `no` unless the byte at `src` is from `lo` to `hi`."
  [src lo hi no]
  (let [lo (max lo 0)
        hi (min hi 255)]
    (if (> lo hi)
      [[:jmp no]]
      [(gen/with-operand :lda src)
       (when (pos? lo) [[:cmp [:imm lo]] [:bcc no]])
       (when (< hi 255) [[:cmp [:imm (inc hi)]] [:bcs no]])])))

(defn- when-overlapping
  "Run `then` if the player's box [left top right bottom] (pixels from the
  top left of their picture) overlaps `target`, a box on the screen. (The
  same test a swing of the stick will use for what it hits.)"
  [[left top right bottom] [x1 y1 x2 y2] then]
  (let [apart (asm/genlabel "apart")]
    [(within (gen/whole :player/x) (- x1 right) (- x2 left) apart)
     (within (gen/whole :player/y) (- y1 bottom) (- y2 top) apart)
     then
     [:label apart]]))

(defn- offset-within
  "Branch to `no` unless `a` minus `b` (one-byte operands: positions on
  the screen) is from `lo` to `hi`."
  [a b lo hi no]
  [(gen/with-operand :lda a) [:sec] (gen/with-operand :sbc b)
   [:clc] [:adc [:imm (bit-and (- lo) 0xFF)]]
   [:cmp [:imm (inc (- hi lo))]] [:bcs no]])

(defn- when-overlapping-enemy
  "Run `then` if the player's box [left top right bottom] (as for
  `when-overlapping`) overlaps the feet of enemy number `index`, wherever
  it is now."
  [index [x1 y1 x2 y2] then]
  (let [apart (asm/genlabel "clear")
        {:keys [left top right bottom]} feet]
    [(offset-within [:+ :enemy-x index] (gen/whole :player/x) (- x1 right) (- x2 left) apart)
     (offset-within [:+ :enemy-y index] (gen/whole :player/y) (- y1 bottom) (- y2 top) apart)
     then
     [:label apart]]))

(defn- feet-of
  "The box of a character's feet: where it bumps into things."
  [{:keys [x y]}]
  (let [{:keys [left top right bottom]} feet]
    [(+ x left) (+ y top) (+ x right) (+ y bottom)]))

(def feet-in-way
  (sub/sub
   :feet-in-way
   "A is 1 if the player's feet are in something solid, or in a character
  standing in the room, and 0 if the way is clear."
   (let [hit (asm/genlabel "bumped")
         done (asm/genlabel "free")
         {:keys [left top right bottom]} feet]
     [(for [[dx dy] [[left top] [right top] [left bottom] [right bottom]]]
        [(rt/probe (gen/whole :player/x) dx (gen/whole :player/y) dy)
         (rt/on-kinds [:solid :sign] [[:jmp hit]])])
      (by-room (fn [name]
                 (for [who (standing-in name)]
                   (alive who (if-let [index (:index who)]
                                (when-overlapping-enemy index [left top right bottom] [[:jmp hit]])
                                (when-overlapping [left top right bottom] (feet-of who) [[:jmp hit]]))))))
      [:lda [:imm 0]]
      [:jmp done]
      [:label hit]
      [:lda [:imm 1]]
      [:label done]])))

(defn- leave-room
  "The player's gone past the `edge` of the room (:left :right :up :down):
  into the room on that side, if there is one, and otherwise back to where
  they were (`put-back`). Arriving puts them just inside the opposite edge."
  [edge put-back]
  (let [arrive ({:left  #(gen/move16 :player/x [:imm (gen/fixed 222)])
                 :right #(gen/move16 :player/x [:imm (gen/fixed 2)])
                 :up    #(gen/move16 :player/y [:imm (gen/fixed 200)])
                 :down  #(gen/move16 :player/y [:imm (gen/fixed 2)])} edge)
        exits (into {} world/rooms)]
    (by-room
     (fn [name]
       (if-let [to (get-in exits [name :exits edge])]
         [(gen/move :room [:imm (world/room-number to)])
          (arrive)
          (rt/restart)]             ; start the new room afresh
         put-back)))))

(defn- corner-solid
  "Run `then` if the corner [dx dy] of the player's feet is in something solid."
  [[dx dy] then]
  [(rt/probe (gen/whole :player/x) dx (gen/whole :player/y) dy)
   (rt/on-kinds [:solid :sign] then)])

(defn- slide-round
  "Inside `step`, when the player has walked into something: put them back
  (`revert`) and, if only one of the two corners of the feet at the front
  is in a wall, nudge them a pixel across (away from the wall), so they
  slide round the corner instead of sticking to it. Walking into the
  middle of a wall, or into a person, only puts them back."
  [axis sign revert]
  (let [horizontal? (= axis :player/x)
        {:keys [left top right bottom]} feet
        front (if horizontal?
                (if (= sign :+) right left)
                (if (= sign :+) bottom top))
        [first-corner second-corner] (if horizontal?
                                       [[front top] [front bottom]]
                                       [[left front] [right front]])
        across (gen/whole (if horizontal? :player/y :player/x))
        nudge (fn [more?]
                [(gen/with-operand (if more? :inc :dec) across)
                 (call feet-in-way)
                 (gen/when-flag :not-zero
                                (gen/with-operand (if more? :dec :inc) across))])
        first-blocked (asm/genlabel "corner")
        done (asm/genlabel "slid")]
    [(corner-solid first-corner [[:jmp first-blocked]])
     ;; the first corner is clear: if the second isn't, nudge towards the first
     (corner-solid second-corner [revert (nudge false) [:jmp done]])
     revert
     [:jmp done]
     [:label first-blocked]
     (corner-solid second-corner [revert [:jmp done]])
     revert
     (nudge true)
     [:label done]]))

(defn- step
  "What walking in a direction does. `axis` is :player/x or :player/y,
  `sign` which way it changes, `facing` which way that makes the player
  face, and the player crosses `edge` when the whole pixels of `axis` are
  `edge-at` or more (walking past 0 wraps round to 255, so that's how
  walking off the left or top shows up) but less than `edge-until`, if
  that's given (going off the top, the player has to get their feet
  into the top row of the room first, or any column would be a way out)."
  [button {:keys [axis sign facing edge edge-at edge-until]}]
  (let [done (asm/genlabel "stepped")
        revert (gen/move16 axis :rt/old)
        leave [(leave-room edge revert) [:jmp done]]]
    (rt/when-held button
                  [(gen/move :facing [:imm (.indexOf art/directions facing)])
                   (gen/move :moving [:imm 1])
                   (gen/move16 :rt/old axis)                       ; where we were
                   ((if (= sign :+) gen/add16 gen/sub16) axis :speed)
                   [:lda (gen/whole axis)]
                   [:cmp [:imm edge-at]]
                   (gen/when-flag :carry
                                  (if edge-until
                                    [[:cmp [:imm edge-until]]
                                     ;; (only if the feet are clear of the wall there)
                                     (gen/when-flag :no-carry
                                                    (call feet-in-way)
                                                    (gen/when-flag :zero leave))]
                                    leave))
                   (call feet-in-way)
                   (gen/when-flag :not-zero (slide-round axis sign revert))
                   [:label done]])))

(def enter-door
  (sub/sub
   :enter-door
   "If the player's feet are in a door, go through it: into the room it
  leads to (see :door in poco.world), at the place it says."
   [(rt/probe (gen/whole :player/x) (quot (+ (:left feet) (:right feet)) 2)
              (gen/whole :player/y) (:top feet))
    (rt/on-kinds
     [:door]
     (by-room
      (fn [name]
        (if-let [{:keys [to x y facing]} (get-in (into {} world/rooms) [name :door])]
          [(gen/move :room [:imm (world/room-number to)])
           (gen/move16 :player/x [:imm (gen/fixed x)])
           (gen/move16 :player/y [:imm (gen/fixed y)])
           (gen/move :facing [:imm (.indexOf art/directions facing)])
           (rt/restart)]
          []))))]))

(def pick-up
  (sub/sub
   :pick-up
   "If the player's feet are on an item lying about, take it: it's theirs
  (a bit of `items`), the game says so, and when that's read the room is
  drawn again without it. An item
  that's been taken is still there as far as the map goes, so it only
  counts the first time."
   [(rt/probe (gen/whole :player/x) (quot (+ (:left feet) (:right feet)) 2)
              (gen/whole :player/y) (quot (+ (:top feet) (:bottom feet)) 2))
    (rt/on-kinds
     [:pickup]
     (by-room
      (fn [name]
        (if-let [{:keys [item]} (:pickup (get (into {} world/rooms) name))]
          [[:lda :items]
           [:and [:imm (item-bit item)]]
           (gen/when-flag :zero
                          [:lda :items]
                          [:ora [:imm (item-bit item)]]
                          [:sta :items]
                          (gen/move :message [:imm (talk/number [:found item])])
                          (rt/transition :talk))]
          []))))]))

(def read-sign
  (sub/sub
   :read-sign
   "A reads a sign: if the player faces one, close enough, the game says
  what it says (the :sign of the room, in poco.world)."
   (let [done (asm/genlabel "read")
         {:keys [left right top bottom]} feet
         middle (quot (+ left right) 2)
         ahead {:down [middle (+ bottom 4)] :up [middle (- top 4)]
                :left [(- left 4) (quot (+ top bottom) 2)] :right [(+ right 4) (quot (+ top bottom) 2)]}]
     (rt/when-pressed
      :a
      [(for [[i direction] (map-indexed vector art/directions)
             :let [[dx dy] (ahead direction)]]
         [[:lda :facing]
          [:cmp [:imm i]]
          (gen/when-flag :equal
                         (rt/probe (gen/whole :player/x) dx (gen/whole :player/y) dy)
                         (rt/on-kinds
                          [:sign]
                          (by-room
                           (fn [name]
                             (if (:sign (get (into {} world/rooms) name))
                               [(gen/move :message [:imm (talk/number [:sign name])])
                                (rt/transition :talk)]
                               []))))
                         [:jmp done])])
       [:label done]]))))

(def move-player
  (sub/sub
   :move-player
   "Walk with the D-pad: a step in each direction held, then put back
  anything that's gone into something solid. x and y are done separately,
  so the player slides along a wall."
   [(gen/move :moving [:imm 0])
    [:lda :swing]
    (gen/when-flag
     :zero
     ;; diagonal (a left or right held, and an up or down too): the slower speed
    (gen/move16 :speed [:imm (gen/fixed walk-speed)])
    [:lda :pad/held]
    [:and [:imm (bit-or (hw/buttons :left) (hw/buttons :right))]]
    (gen/when-flag :not-zero
                   [:lda :pad/held]
                   [:and [:imm (bit-or (hw/buttons :up) (hw/buttons :down))]]
                   (gen/when-flag :not-zero
                                  (gen/move16 :speed [:imm (gen/fixed diagonal-speed)])))
    (step :right {:axis :player/x :sign :+ :facing :right :edge :right :edge-at 240})
    (step :left  {:axis :player/x :sign :- :facing :left  :edge :left  :edge-at 240})
    (step :down  {:axis :player/y :sign :+ :facing :down  :edge :down  :edge-at 208})
    (step :up    {:axis :player/y :sign :- :facing :up    :edge :up    :edge-at 224 :edge-until 246})
    ;; the walk: standing still is pose 0, and walking goes round pose 1 to 4
    [:lda :moving]
    (gen/if-flag :not-zero
                 [[:inc :walk]
                  [:lda :walk]
                  (repeat frames-per-step [:lsr :a])
                  [:and [:imm 3]]
                  [:clc] [:adc [:imm 1]]
                  [:sta :pose]]
                 [(gen/move :walk [:imm 0])
                  (gen/move :pose [:imm 0])]))]))

(defn- swing-box
  "Where the stick hits when the player faces `direction`: a box just past
  their feet, `swing-reach` deep (see `feet-of` for the box of an enemy it
  has to overlap)."
  [direction]
  (let [{:keys [left top right bottom]} feet
        r swing-reach]
    (case direction
      :right [(inc right) top (+ right r) bottom]
      :left  [(- left r) top (dec left) bottom]
      :down  [left (inc bottom) right (+ bottom r)]
      :up    [left (- top r) right (dec top)])))

(def strike
  (sub/sub
   :strike
   "The stick comes down: every enemy in the room whose feet are in the box
  in front of the player loses a hit, and flickers for a moment."
   (by-room
    (fn [name]
      (for [{:keys [index] :as who} (enemies-in name)]
        (alive who
               (for [[i direction] (map-indexed vector art/directions)]
                 [[:lda :facing]
                  [:cmp [:imm i]]
                  (gen/when-flag :equal
                                 (when-overlapping-enemy index (swing-box direction)
                                                   [[:dec [:+ :enemy-hp index]]
                                                    [:lda [:imm hurt-frames]]
                                                    [:sta [:+ :enemy-hurt index]]]))])))))))

(def swing-stick
  (sub/sub
   :swing-stick
   "B swings the stick, if the player has it: they stand still for
  `swing-frames`, drawn in the swing's two pictures, one after the other
  (poses 5 and 6)."
   [[:lda :swing]
    (gen/if-flag
     :not-zero
     [[:dec :swing]
      [:lda :swing]
      (gen/if-flag :zero
                   (gen/move :pose [:imm 0])
                   [[:cmp [:imm (quot swing-frames 2)]]
                    (gen/if-flag :carry
                                 (gen/move :pose [:imm 5])
                                 (gen/move :pose [:imm 6]))
                    ;; the stick comes down as the second picture starts
                    [:lda :swing]
                    [:cmp [:imm (dec (quot swing-frames 2))]]
                    (gen/when-flag :equal (call strike))])]
     (rt/when-pressed
      :b
      [[:lda :items]
       [:and [:imm (item-bit :stick)]]
       (gen/when-flag :not-zero
                      (gen/move :swing [:imm swing-frames])
                      (gen/move :walk [:imm 0])
                      (gen/move :pose [:imm 5]))]))]))

(defn- enemy-step
  "Inside `move-enemy`: enemy number `index` takes a pixel's step along
  `axis` (:enemy-x or :enemy-y), `more?` (right or down) or not. It goes
  back if that's off the room, in something solid, or into the player."
  [index axis more?]
  (let [cell [:+ axis index]
        {:keys [left top right bottom]} feet
        blocked (asm/genlabel "blocked")
        ok (asm/genlabel "moved")]
    [[(if more? :inc :dec) cell]
     [:lda cell]
     [:cmp [:imm (if (= axis :enemy-x) 224 208)]] ; (walking off 0 wraps round to 255)
     (gen/when-flag :carry [:jmp blocked])
     (for [[dx dy] [[left top] [right top] [left bottom] [right bottom]]]
       [(rt/probe [:+ :enemy-x index] dx [:+ :enemy-y index] dy)
        (rt/on-kinds [:solid :sign] [[:jmp blocked]])])
     (when-overlapping-enemy index [left top right bottom] [[:jmp blocked]])
     [:jmp ok]
     [:label blocked]
     [(if more? :dec :inc) cell]
     [:label ok]]))

(defn- move-enemy
  "Inside `move-enemies`: what enemy number `index` does this frame. If
  it's not stunned: a pause or a way it's walking, a pixel at a time,
  until its timer runs out, when it picks again at random."
  [index]
  (let [done (asm/genlabel "enemy-done")
        timer [:+ :enemy-timer index]
        dir [:+ :enemy-dir index]
        flip [:+ :enemy-flip index]]
    [[:lda [:+ :enemy-hurt index]]
     (gen/when-flag :not-zero [:jmp done])
     ;; think again?
     [:lda timer]
     (gen/if-flag
      :zero
      [(call rt/random)
       [:and [:imm (* (dec enemy-pause-odds) 0x40)]] ; the top two bits of the number
       [:cmp [:imm (* (dec enemy-pause-odds) 0x40)]]
       (gen/if-flag :equal
                    [:lda [:imm 4]]
                    [[(call rt/random)] [:and [:imm 3]]])
       [:sta dir]
       (call rt/random)
       [:and [:imm enemy-walk-spread]]
       [:clc] [:adc [:imm enemy-walk-frames]]
       [:sta timer]]
      [:dec timer])
     [:lda dir]
     [:cmp [:imm 4]]
     (gen/when-flag :carry [:jmp done])
     (when (> enemy-walk-every 1)
       [[:lda :rt/frame] [:and [:imm (dec enemy-walk-every)]]
        (gen/when-flag :not-zero [:jmp done])])
     [:lda dir]
     (for [[i [axis more? left?]] (map-indexed vector [[:enemy-x true false] [:enemy-x false true]
                                                       [:enemy-y true false] [:enemy-y false false]])
           :let [next (asm/genlabel "not-this-way")]]
       [[:cmp [:imm i]]
        (gen/when-flag :not-zero [:jmp next])
        (when (= axis :enemy-x)
          [[:lda [:imm (if left? 1 0)]] [:sta flip]])
        (enemy-step index axis more?)
        [:jmp done]
        [:label next]])
     [:label done]]))

(def move-enemies
  (sub/sub
   :move-enemies
   "Enemies that are in the room and not stunned walk about: see `move-enemy`."
   (by-room
    (fn [name]
      (for [{:keys [index] :as who} (enemies-in name)]
        (alive who (move-enemy index)))))))

(def draw-characters
  (sub/sub
   :draw-characters
   "Draw the people and enemies of the room (:npcs and :enemies in
  poco.world): not the enemies that are out of hits, and one that's just
  been hit flickers. An enemy that's walking takes steps (facing the way it
  last went, left or right); the people stand where the room puts them."
   (by-room
    (fn [name]
      (for [{:keys [kind x y index] :as who} (standing-in name)
            :let [skip (asm/genlabel "unseen")
                  walking (asm/genlabel "walking")
                  drawn (asm/genlabel "drawn")]]
        (alive who
               [(when index
                  [[:lda [:+ :enemy-hurt index]]
                   (gen/when-flag :not-zero
                                  [:dec [:+ :enemy-hurt index]]
                                  [:lda [:+ :enemy-hurt index]]
                                  [:and [:imm 2]]
                                  (gen/when-flag :not-zero [:jmp skip]))])
                (if index
                  (let [at [[:+ :enemy-x index] [:+ :enemy-y index]]
                        draw (fn [step flip?]
                               (apply rt/metasprite (concat at [(art/character-pieces kind step flip?)])))]
                    (if (art/walks? kind)
                      [;; standing still (4), or which of the two steps
                       [:lda [:+ :enemy-dir index]]
                       [:cmp [:imm 4]]
                       (gen/when-flag :carry (draw 0 false) [:jmp drawn])
                       [:lda [:+ :enemy-flip index]]
                       (gen/if-flag :zero
                                    [[:lda :rt/frame] [:and [:imm 8]]
                                     (gen/if-flag :zero (draw 1 false) (draw 2 false))]
                                    [[:lda :rt/frame] [:and [:imm 8]]
                                     (gen/if-flag :zero (draw 1 true) (draw 2 true))])
                       [:label drawn]]
                      (draw 0 false)))
                  (rt/metasprite [:imm x] [:imm y] (art/character-pieces kind)))
                [:label skip]]))))))

(def talk-to
  (sub/sub
   :talk-to
   "A talks to someone: if the player faces a character that has something
  to say (the :says of an :npc in poco.world), close enough, the game says
  it."
   (let [done (asm/genlabel "talked")
         {:keys [left top right bottom]} feet
         reach 4
         ahead {:down  [left top right (+ bottom reach)] :up    [left (- top reach) right bottom]
                :left  [(- left reach) top right bottom] :right [left top (+ right reach) bottom]}]
     (rt/when-pressed
      :a
      [(for [[i direction] (map-indexed vector art/directions)]
         [[:lda :facing]
          [:cmp [:imm i]]
          (gen/when-flag :equal
                         (by-room
                          (fn [name]
                            (for [{:keys [kind says] :as who} (:npcs (get (into {} world/rooms) name))
                                  :when says]
                              (when-overlapping (ahead direction) (feet-of who)
                                                [(gen/move :message [:imm (talk/number [:npc name kind])])
                                                 (rt/transition :talk)
                                                 [:jmp done]]))))
                         [:jmp done])])
       [:label done]]))))

(def draw-player
  (sub/sub
   :draw-player
   "Draw the picture for the way the player faces and the step they're on.
  The loops run when the ROM is built: the NES has the instructions for
  every picture written out, and picks one."
   (let [done (asm/genlabel "drawn")]
     [(for [[i direction] (map-indexed vector art/directions)]
        [[:lda :facing]
         [:cmp [:imm i]]
         (gen/when-flag :equal
                        (for [pose (range art/steps)]
                          [[:lda :pose]
                           [:cmp [:imm pose]]
                           (gen/when-flag :equal
                                          (rt/metasprite (gen/whole :player/x) (gen/whole :player/y)
                                                         (art/pose-pieces direction pose))
                                          [:jmp done])]))])
      [:label done]])))

;; ---------------------------------------------------------------------------
;; The items screen

(def draw-icons
  "Inside draw-screen: over the empty frame of each slot whose item the
  player has, that item's picture (two rows of two tiles)."
  (for [[i {:keys [id]}] (map-indexed vector items/items)
        :let [[c r] (menu/slot-block i)
              [top-left top-right bottom-left bottom-right]
              (tileset/block art/tiles (keyword "item" (name id)))]]
    [[:lda :items]
     [:and [:imm (menu/bit-of i)]]
     (gen/when-flag :not-zero
                    (rt/at (* 2 c) (* 2 r))
                    [:lda [:imm top-left]] [:sta :ppudata]
                    [:lda [:imm top-right]] [:sta :ppudata]
                    (rt/at (* 2 c) (inc (* 2 r)))
                    [:lda [:imm bottom-left]] [:sta :ppudata]
                    [:lda [:imm bottom-right]] [:sta :ppudata])]))

(defn- queue-line
  "Queue a line of words for the background, from the bytes :rt/pointer
  points to (from `offset`), written at `row`. X is the queue's end."
  [row offset]
  (let [address (rt/tile-address menu/text-column row)]
    [[:lda [:imm (bit-shift-right address 8)]] [:sta :video-queue :x] [:inx]
     [:lda [:imm (bit-and address 0xFF)]]      [:sta :video-queue :x] [:inx]
     [:lda [:imm menu/text-columns]]           [:sta :video-queue :x] [:inx]
     [:ldy [:imm offset]]
     (gen/do-while :not-equal
                   [:lda [:ind :rt/pointer] :y]
                   [:sta :video-queue :x]
                   [:inx]
                   [:iny]
                   [:cpy [:imm (+ offset menu/text-columns)]])]))

(def show-item-text
  (sub/sub
   :show-item-text
   "Write what the selected slot's item is called, and what it says, to
  the background (at the next vertical blank). Each slot's words are 64
  bytes in a table, so slot s is at s * 64: the high byte is s / 4, and
  the low byte (s mod 4) * 64. A slot whose item the player doesn't have
  says ?????"
   [[:ldx :selected]
    [:lda :bit-of :x]
    [:and :items]
    (gen/if-flag :not-zero
                 [[:lda :selected] [:lsr :a] [:lsr :a] [:sta :rt/temp]
                  [:lda :selected] [:and [:imm 3]]
                  (repeat 6 [:asl :a])
                  [:clc] [:adc [:imm [:lo :item-text]]] [:sta :rt/pointer]
                  [:lda :rt/temp] [:adc [:imm [:hi :item-text]]] [:sta [:+ :rt/pointer 1]]]
                 (gen/move16 :rt/pointer [:imm :item-unknown]))
    [:ldx :rt/queue-end]
    (queue-line menu/name-row 0)
    (queue-line menu/doc-row 32)
    [:stx :rt/queue-end]]))

(def move-cursor
  (sub/sub
   :move-cursor
   "The D-pad moves the cursor round the eight slots (two rows of four),
  wrapping round at the sides. Up and down both swap the rows."
   [(rt/when-pressed :right
                     [[:lda :selected] [:and [:imm 3]] [:cmp [:imm 3]]
                      (gen/if-flag :equal
                                   [[:lda :selected] [:sec] [:sbc [:imm 3]]]
                                   [[:lda :selected] [:clc] [:adc [:imm 1]]])
                      [:sta :selected]
                      (gen/move :redraw [:imm 1])])
    (rt/when-pressed :left
                     [[:lda :selected] [:and [:imm 3]]
                      (gen/if-flag :zero
                                   [[:lda :selected] [:clc] [:adc [:imm 3]]]
                                   [[:lda :selected] [:sec] [:sbc [:imm 1]]])
                      [:sta :selected]
                      (gen/move :redraw [:imm 1])])
    (for [button [:up :down]]
      (rt/when-pressed button
                       [[:lda :selected] [:eor [:imm 4]] [:sta :selected]
                        (gen/move :redraw [:imm 1])]))]))

(def draw-cursor
  (sub/sub
   :draw-cursor
   "A box round the selected slot, drawn as four corners, and blinking."
   (let [tile (tileset/tile art/tiles :cursor)
         p (:palette art/cursor-look)]
     [[:lda :rt/frame]
      [:and [:imm 16]]
      (gen/when-flag :zero
                     [:ldx :selected]
                     [:lda :slot-x :x] [:sta :rt/temp]
                     [:lda :slot-y :x] [:sta :rt/temp2]
                     (rt/metasprite :rt/temp :rt/temp2
                                    [[tile 0 0 p]
                                     [tile 8 0 (bit-or p 0x40)]
                                     [tile 0 8 (bit-or p 0x80)]
                                     [tile 8 8 (bit-or p 0xC0)]]))])))

;; ---------------------------------------------------------------------------
;; The text box

(defn- blank-row
  "Inside draw-screen: a row of the box with nothing in it."
  [row]
  [(rt/at 0 row)
   [:lda [:imm (tileset/tile art/tiles :box-font)]]
   [:ldx [:imm 32]]
   (gen/do-while :not-equal [:sta :ppudata] [:dex])])

(defn- text-row
  "Inside draw-screen: a row of the box, from the 32 bytes :rt/pointer
  points to (from `offset`)."
  [row offset]
  [(rt/at 0 row)
   [:ldy [:imm offset]]
   (gen/do-while :not-equal
                 [:lda [:ind :rt/pointer] :y]
                 [:sta :ppudata]
                 [:iny]
                 [:cpy [:imm (+ offset 32)]])])

(defn- draw-box
  "Inside draw-screen: the box, four rows of tiles from `row` down, and the
  last palette of the screen's attribute bytes (`attribute`, 8 of them,
  for four rows of tiles) so the box is all in palette 3."
  [row attribute]
  [(blank-row row)
   (text-row (+ row 1) 0)
   (text-row (+ row 2) 32)
   (blank-row (+ row 3))
   (video-address attribute)
   [:lda [:imm 0xFF]]
   [:ldx [:imm 8]]
   (gen/do-while :not-equal [:sta :ppudata] [:dex])])

(def box-enter
  "Show the box over the room, at the bottom, or at the top if that's where
  the player is. Message number `message` is 64 bytes at message * 64 in
  the table."
  (let [at-top (asm/genlabel "boxtop")
        done (asm/genlabel "boxed")]
    (rt/draw-screen
     [[:lda :message] [:lsr :a] [:lsr :a] [:sta :rt/temp]
      [:lda :message] [:and [:imm 3]]
      (repeat 6 [:asl :a])
      [:clc] [:adc [:imm [:lo :messages]]] [:sta :rt/pointer]
      [:lda :rt/temp] [:adc [:imm [:hi :messages]]] [:sta [:+ :rt/pointer 1]]
      [:lda (gen/whole :player/y)]
      [:cmp [:imm 112]]
      [:bcs at-top]
      (draw-box 24 0x23F0)
      [:jmp done]
      [:label at-top]
      (draw-box 0 0x23C0)
      [:label done]])))

;; ---------------------------------------------------------------------------
;; States

(def title
  {:enter  [(rt/draw-screen (world-palettes) (rt/background :title))]
   :update [(rt/when-pressed :start
                             [(rt/stir-random)
                              start-game
                              (rt/transition :room)])]})

(def room
  "Being in a room. Arriving draws it, and `restart` (walking into
  another room) arrives afresh."
  {:enter  [(draw-room)]
   :update [(call swing-stick)
            (call move-player)
            (call enter-door)
            (call pick-up)
            (call read-sign)
            (call draw-player)
            (call talk-to)
            (call move-enemies)
            (call draw-characters)
            (rt/when-pressed :start (rt/transition :items))
            (rt/when-pressed :select (rt/transition :title))]})

(def talk
  "Reading a message. A or B goes back to the room (which draws itself
  again, over the box). The player stands where they were."
  {:enter  box-enter
   :update [(call draw-player)
            (rt/when-pressed :a (rt/transition :room))
            (rt/when-pressed :b (rt/transition :room))]})

(def items-screen
  "The items screen. Start or B goes back to the room."
  {:enter  [(rt/draw-screen (item-palettes) (rt/background :items) draw-icons)
            (gen/move :redraw [:imm 1])]
   :update [(call move-cursor)
            [:lda :redraw]
            (gen/when-flag :not-zero
                           (call show-item-text)
                           (gen/move :redraw [:imm 0]))
            (call draw-cursor)
            (rt/when-pressed :start (rt/transition :room))
            (rt/when-pressed :b (rt/transition :room))]})

;; ---------------------------------------------------------------------------
;; The debugger's overlay

(def overlays
  [{:name "Feet"
    :doc "The box of the player's feet, the only part that bumps into things."
    :draw (fn [{:keys [player] state :rt.state}]
            (when (= state :room)
              (let [{:keys [left top right bottom]} feet]
                [[:rect {:x (+ (:x player) left) :y (+ (:y player) top)
                         :w (- (inc right) left) :h (- (inc bottom) top)
                         :color :lime :label "feet"}]])))}
   {:name "Swing"
    :doc "Where the stick hits, while it's swinging."
    :draw (fn [{:keys [player facing swing] state :rt.state}]
            (when (and (= state :room) (pos? (or swing 0)))
              (let [direction (if (number? facing) (nth art/directions facing) facing)
                    [x1 y1 x2 y2] (swing-box direction)]
                [[:rect {:x (+ (:x player) x1) :y (+ (:y player) y1)
                         :w (inc (- x2 x1)) :h (inc (- y2 y1))
                         :color :red :label "stick"}]])))}])

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (rt/program
   {:name "poco.nes"
    :variables variables
    :tiles (:tiles art/tiles)
    :palettes art/palettes
    :screens (assoc world/screens :items menu/screen-data)
    :tables {:world-palettes (vec (take 16 art/palettes))
             :item-palettes menu/palettes
             :bit-of menu/bit-of
             :slot-x (mapv #(first (menu/slot-pixels %)) (range items/slots))
             :slot-y (mapv #(second (menu/slot-pixels %)) (range items/slots))
             :item-text menu/text-table
             :item-unknown menu/unknown
             :messages talk/table}
    :overlays overlays
    ;; the pictures of every step of every direction take a lot of room
    :prg-size 32768
    :initial :title
    :states [[:title title]
             [:room room]
             [:items items-screen]
             [:talk talk]]}))
