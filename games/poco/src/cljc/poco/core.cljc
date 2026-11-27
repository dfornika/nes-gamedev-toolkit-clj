(ns poco.core
  "The Legend of PoCo: walk round a little world, one screen at a time.

  The D-pad walks (the player faces the way they're going, and their legs
  move while they do); walk off the edge of a room, through a gap, and you
  arrive in the next one. B changes the color of the backdrop (the
  color 'behind' everything: look at the grass). A reads a sign (face it first). Start opens the items
  screen (and closes it again); Select goes back to the title.

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

(def variables
  [[:room   [:enum (zipmap (range) world/room-names)] {:doc "Which room the player is in"}]
   [:player {:x :u8.8, :y :u8.8}]                      ; fixed point: it can move by parts of a pixel
   [:facing [:enum (zipmap (range) art/directions)] {:doc "The way the player faces"}]
   [:moving :bool {:doc "Walking this frame"}]
   [:speed  :u8.8 {:doc "Pixels to walk this frame: less when going diagonally"}]
   [:walk   :u8   {:doc "Counts frames of walking"}]
   [:pose   :u8   {:doc "What to draw: 0 standing, 1 to 4 the steps of the walk"}]
   [:color  :u8   {:doc "Which of the backdrop colors to show"}]
   [:items  [:bits (into {} (map-indexed (fn [i {:keys [id]}] [id i]) items/items))]
    {:doc "The items the player has: a bit each, in the order of poco.items"}]
   [:message :u8 {:doc "Which of poco.talk's messages to show"}]
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
  color A and B chose. (A function: each use needs labels of its own.)"
  []
  [(video-address 0x3F00)
   [:lda :color] [:and [:imm 7]] [:tax]
   [:lda :backdrops :x]
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

(defn draw-room
  "Draw the room the player is in (the screen goes blank while it does)."
  []
  (rt/draw-screen
   (world-palettes)
   (by-room #(rt/background %))
   (by-room take-away-pickup)))

(def start-game
  [(gen/move :room [:imm (world/room-number (:room world/start))])
   (gen/move16 :player/x [:imm (gen/fixed (:x world/start))])
   (gen/move16 :player/y [:imm (gen/fixed (:y world/start))])
   (gen/move :facing [:imm 0])
   (gen/move :moving [:imm 0])
   (gen/move :walk [:imm 0])
   (gen/move :pose [:imm 0])
   (gen/move :items [:imm menu/starting-bits])
   (gen/move :selected [:imm 0])])

(defn- on-feet-solid
  "Run `then` if any corner of the player's feet is in something solid,
  otherwise `else`."
  [then else]
  (let [hit (asm/genlabel "bumped")
        done (asm/genlabel "free")
        {:keys [left top right bottom]} feet]
    [(for [[dx dy] [[left top] [right top] [left bottom] [right bottom]]]
       [(rt/probe (gen/whole :player/x) dx (gen/whole :player/y) dy)
        (rt/on-kinds [:solid :sign] [[:jmp hit]])])
     else
     [:jmp done]
     [:label hit]
     then
     [:label done]]))

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

(defn- step
  "What walking in a direction does. `axis` is :player/x or :player/y,
  `sign` which way it changes, `facing` which way that makes the player
  face, and the player crosses `edge` when the whole pixels of `axis` are
  `edge-at` or more (walking past 0 wraps round to 255, so that's how
  walking off the left or top shows up)."
  [button {:keys [axis sign facing edge edge-at]}]
  (rt/when-held button
                [(gen/move :facing [:imm (.indexOf art/directions facing)])
                 (gen/move :moving [:imm 1])
                 (gen/move16 :rt/old axis)                       ; where we were
                 ((if (= sign :+) gen/add16 gen/sub16) axis :speed)
                 [:lda (gen/whole axis)]
                 [:cmp [:imm edge-at]]
                 (gen/if-flag :carry
                              (leave-room edge (gen/move16 axis :rt/old))
                              (on-feet-solid (gen/move16 axis :rt/old) nil))]))

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
    (step :up    {:axis :player/y :sign :- :facing :up    :edge :up    :edge-at 224})
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
                  (gen/move :pose [:imm 0])])]))

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
                        (for [pose (range 5)]
                          [[:lda :pose]
                           [:cmp [:imm pose]]
                           (gen/when-flag :equal
                                          (rt/metasprite (gen/whole :player/x) (gen/whole :player/y)
                                                         (art/pose-pieces direction pose))
                                          [:jmp done])]))])
      [:label done]])))

(def change-backdrop
  (sub/sub
   :change-backdrop
   "B steps to the next backdrop color. The PPU can only be changed in
  vertical blank, so this queues a write to the first color of its palette
  memory ($3F00)."
   [(rt/when-pressed :b [:inc :color])
    (rt/queue 0x3F00
              [[:lda :color] [:and [:imm 7]] [:tay] [:lda :backdrops :y]])]))

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
                             [start-game
                              (rt/transition :room)])]})

(def room
  "Being in a room. Arriving draws it, and `restart` (walking into
  another room) arrives afresh."
  {:enter  [(draw-room)]
   :update [(call move-player)
            (call enter-door)
            (call pick-up)
            (call read-sign)
            (call draw-player)
            (call change-backdrop)
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
                         :color :lime :label "feet"}]])))}])

(defn build
  "Build the cartridge. Returns {:rom the .nes file's bytes, :name, ...}."
  []
  (rt/program
   {:name "poco.nes"
    :variables variables
    :tiles (:tiles art/tiles)
    :palettes art/palettes
    :screens (assoc world/screens :items menu/screen-data)
    :tables {:backdrops art/backgrounds
             :world-palettes (vec (take 16 art/palettes))
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
