(ns poco.core
  "The Legend of PoCo: walk round a little world, one screen at a time.

  The D-pad walks (the player faces the way they're going, and their legs
  move while they do); walk off the edge of a room, through a gap, and you
  arrive in the next one. A and B change the color of the backdrop (the
  color 'behind' everything: look at the grass). Start goes back to the
  title.

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
            [cartridj.runtime :as rt]
            [poco.art :as art]
            [poco.world :as world]))

;; ---------------------------------------------------------------------------
;; Numbers to tune

(def walk-speed
  "How far the player walks each frame, in pixels."
  [:imm (gen/fixed 1.5)])

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
   [:walk   :u8   {:doc "Counts frames of walking"}]
   [:pose   :u8   {:doc "What to draw: 0 standing, 1 to 4 the steps of the walk"}]
   [:color  :u8   {:doc "Which of the backdrop colors to show"}]])

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

(defn draw-room
  "Draw the room the player is in (the screen goes blank while it does)."
  []
  (rt/draw-screen
   (by-room #(rt/background %))))

(def start-game
  [(gen/move :room [:imm (world/room-number (:room world/start))])
   (gen/move16 :player/x [:imm (gen/fixed (:x world/start))])
   (gen/move16 :player/y [:imm (gen/fixed (:y world/start))])
   (gen/move :facing [:imm 0])
   (gen/move :moving [:imm 0])
   (gen/move :walk [:imm 0])
   (gen/move :pose [:imm 0])])

(defn- on-feet-solid
  "Run `then` if any corner of the player's feet is in something solid,
  otherwise `else`."
  [then else]
  (let [hit (asm/genlabel "bumped")
        done (asm/genlabel "free")
        {:keys [left top right bottom]} feet]
    [(for [[dx dy] [[left top] [right top] [left bottom] [right bottom]]]
       [(rt/probe (gen/whole :player/x) dx (gen/whole :player/y) dy)
        (rt/on-kinds [:solid] [[:jmp hit]])])
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
                 ((if (= sign :+) gen/add16 gen/sub16) axis walk-speed)
                 [:lda (gen/whole axis)]
                 [:cmp [:imm edge-at]]
                 (gen/if-flag :carry
                              (leave-room edge (gen/move16 axis :rt/old))
                              (on-feet-solid (gen/move16 axis :rt/old) nil))]))

(def move-player
  (sub/sub
   :move-player
   "Walk with the D-pad: a step in each direction held, then put back
  anything that's gone into something solid. x and y are done separately,
  so the player slides along a wall."
   [(gen/move :moving [:imm 0])
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
   "A steps to the next backdrop color, B to the one before. The PPU can
  only be changed in vertical blank, so this queues a write to the first
  color of its palette memory ($3F00)."
   [(rt/when-pressed :a [:inc :color])
    (rt/when-pressed :b [:dec :color])
    (rt/queue 0x3F00
              [[:lda :color] [:and [:imm 7]] [:tay] [:lda :backdrops :y]])]))

;; ---------------------------------------------------------------------------
;; States

(def title
  {:enter  [(rt/draw-screen (rt/background :title))]
   :update [(rt/when-pressed :start
                             [start-game
                              (rt/transition :room)])]})

(def room
  "Being in a room. Arriving draws it, and `restart` (walking into
  another room) arrives afresh."
  {:enter  [(draw-room)]
   :update [(call move-player)
            (call draw-player)
            (call change-backdrop)
            (rt/when-pressed :start (rt/transition :title))]})

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
    :screens world/screens
    :tables {:backdrops art/backgrounds}
    :overlays overlays
    :initial :title
    :states [[:title title]
             [:room room]]}))
