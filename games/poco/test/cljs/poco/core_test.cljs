(ns poco.core-test
  "Plays The Legend of PoCo on the ClojureScript CPU by itself (see
  zeropage.harness): walking, the walk cycle, the walls, and the doors
  between rooms."
  (:require [cljs.test :refer [deftest is testing]]
            [cartridj.chr :as chr]
            [poco.art :as art]
            [poco.menu :as menu]
            [poco.talk :as talk]
            [poco.core :as poco]
            [poco.world :as world]
            [zeropage.harness :as harness]))

(defn- addr [m name] (get (:symbols m) name))
(defn- whole [m name] (harness/peek-at m (inc (addr m name))))
(defn- where [m] [(whole m :player/x) (whole m :player/y)])
(defn- room [m] (nth world/room-names (harness/peek-at m :room)))
(defn- facing [m] (nth art/directions (harness/peek-at m :facing)))
(defn- pose [m] (harness/peek-at m :pose))
(defn- state [m] (get {0 :title 1 :room 2 :items 3 :talk} (harness/peek-at m :rt/state)))

(defn- walk!
  "Hold `button` for `n` frames, then let go."
  [m button n]
  (harness/hold! m button)
  (harness/frames! m n)
  (harness/hold! m)
  (harness/frame! m))

(defn- walk-out!
  "Hold `button` until the room changes (or `limit` frames), then let go
  at once. Returns how many frames it took."
  [m button limit]
  (let [from (room m)]
    (harness/hold! m button)
    (let [n (loop [n 0]
              (if (or (not= from (room m)) (>= n limit))
                n
                (do (harness/frame! m) (recur (inc n)))))]
      (harness/hold! m)
      n)))

(defn- tap! [m button]
  (harness/hold! m button) (harness/frame! m)
  (harness/hold! m) (harness/frame! m))

(defn- game
  "A game, started: in the first room, at the start."
  []
  (let [m (harness/boot! (harness/console (poco/build)))]
    (harness/frames! m 2)
    (tap! m :start)
    (harness/frames! m 3)
    m))

(deftest the-game-starts-at-the-start
  (let [m (game)]
    (is (= (:room world/start) (room m)))
    (is (= [(:x world/start) (:y world/start)] (where m)))
    (is (= :down (facing m)))
    (is (zero? (pose m)) "standing")))

(deftest walking-faces-and-animates
  (let [m (game)]
    (harness/hold! m :right)
    (harness/frames! m 4)
    (is (= :right (facing m)))
    (is (= [1] [(pose m)]) "the first step")
    (let [poses (set (for [_ (range 40)] (do (harness/frame! m) (pose m))))]
      (is (= #{1 2 3 4} poses) "every step of the cycle, and not standing"))
    (harness/hold! m)
    (harness/frames! m 2)
    (is (zero? (pose m)) "standing when they stop")
    (is (= :right (facing m)) "still facing the same way")))

(deftest the-sprites-are-where-the-player-is
  (let [m (game)
        oam (addr m :oam)
        [x y] (where m)
        sprites (for [i (range (count (art/pose-pieces :down 0)))]
                  (mapv #(harness/peek-at m (+ oam (* 4 i) %)) [0 1 2 3]))]
    (is (= (sort (map (fn [[_ _ dx dy]] [(+ y dy) (+ x dx)]) (map (fn [[t dx dy p]] [t p dx dy]) (art/pose-pieces :down 0))))
           (sort (map (fn [[y _ _ x]] [y x]) sprites))))))

(deftest a-teenager-stands-in-the-park
  (let [{:keys [x y]} (first (:enemies (get (into {} world/rooms) :park)))
        pieces (art/character-pieces :teenager)
        player-pieces (count (art/pose-pieces :down 0))
        sprites-after-the-player (fn [m n]
                                   (for [i (range player-pieces (+ player-pieces n))]
                                     (mapv #(harness/peek-at m (+ (addr m :oam) (* 4 i) %)) [0 1 2 3])))]
    (testing "not in the first room"
      (is (every? #(= 0xFF (first %)) (sprites-after-the-player (game) (count pieces)))))
    (testing "in the park, where the room puts them"
      (let [m (game)]
        (walk-out! m :right 300)
        (harness/frames! m 3)
        (is (= :park (room m)))
        (is (= (sort (for [[_ dx dy] pieces] [(+ y dy) (+ x dx)]))
               (sort (map (fn [[sy _ _ sx]] [sy sx]) (sprites-after-the-player m (count pieces))))))))))

(deftest walls-stop-the-player
  (let [m (game)]
    (walk! m :left 80)
    (is (<= 13 (first (where m)) 15) "the trees' edge is at x = 16, and the feet start 3 in")
    (is (= :home (room m)))))

(deftest walking-off-an-edge-goes-to-the-next-room
  (let [m (game)]
    (walk-out! m :right 300)
    (harness/frames! m 3)
    (is (= :park (room m)))
    (is (<= 0 (first (where m)) 10) "just inside the left edge")
    (walk-out! m :left 300)
    (harness/frames! m 3)
    (is (= :home (room m)) "and back again")
    (is (= :left (facing m)))
    (testing "the way is a gap: a wall elsewhere is just a wall"
      (let [m (game)]
        (walk! m :down 200)
        (is (= :home (room m)) "the south of the home room has no exit")
        (is (< (second (where m)) 208))))))

(deftest diagonals-are-no-faster
  (let [straight (let [m (game)]
                   (walk! m :right 20)
                   (- (first (where m)) (:x world/start)))
        [dx dy] (let [m (game) [x y] (where m)]
                  (harness/hold! m :right :up)
                  (harness/frames! m 20)
                  (harness/hold! m)
                  (harness/frame! m)
                  (let [[x2 y2] (where m)] [(- x2 x) (- y2 y)]))]
    (is (= 30 straight))
    (is (<= 20 dx 22) "slower along each axis")
    (is (<= 20 (- dy) 22))
    (is (< (+ (* dx dx) (* dy dy)) (* 1.1 straight straight)) "about as far as a straight line")))

(deftest the-house-has-a-door
  (let [m (game)]
    (walk! m :right 32)
    (is (= :home (room m)))
    (walk-out! m :up 80)
    (harness/frames! m 3)
    (is (= :house (room m)) "walking into the door goes inside")
    (is (= :up (facing m)))
    (is (= [120 176] (where m)) "just inside")
    (testing "and out again"
      (walk-out! m :down 80)
      (harness/frames! m 3)
      (is (= :home (room m)))
      (is (= :down (facing m)))
      (is (= [72 76] (where m)) "in front of the door"))))

(defn- inside-the-house! [m]
  (walk! m :right 32)
  (walk-out! m :up 80)
  (harness/frames! m 3)
  m)

(deftest guests-bring-their-own-sprite-palettes
  (is (= #{} (art/guests-of :teenager)) "the teenager's colors are all in the player's palettes")
  (is (= #{} (art/guests-of :dad)) "the dad is drawn in the player's, for now")
  (testing "everything a character is drawn in is a palette the cartridge has"
    (doseq [kind [:teenager :dad]
            step (range 3)
            direction art/directions
            :let [palettes-used (set (for [[_ _ _ p] (art/character-pieces kind step direction)] (bit-and p 3)))]]
      (is (every? #(< % 4) palettes-used) (str kind step))))
  (testing "no room has two guests"
    (doseq [[room {:keys [npcs enemies]}] world/rooms]
      (is (<= (count (distinct (mapcat #(art/guests-of (:kind %)) (concat npcs enemies)))) 1) (str room)))))

(defn- in-the-park!
  "A game with the stick, in the park next to the teenager: `gap` pixels
  from his feet, on his left, facing `direction`."
  [gap direction]
  (let [m (doto (game) (harness/poke-at! :items 2r1111))
        {:keys [x y]} (first (:enemies (get (into {} world/rooms) :park)))]
    (walk-out! m :right 300)
    (harness/frames! m 3)
    (harness/poke-at! m (inc (addr m :player/x)) (- (+ x 3) gap 13))   ; feet's right edge, 12 in, is `gap` short of his
    (harness/poke-at! m (inc (addr m :player/y)) y)
    (harness/poke-at! m :facing (.indexOf art/directions direction))
    m))

(defn- teenager-hp [m] (harness/peek-at m (addr m :enemy-hp)))

(deftest the-stick-hits-the-teenager
  (testing "close enough, facing him: one hit a swing"
    (let [m (in-the-park! 4 :right)]
      (is (= 3 (teenager-hp m)))
      (tap! m :b)
      (harness/frames! m 16)
      (is (= 2 (teenager-hp m)) "one hit, not one for every frame")
      (harness/frames! m 20)
      (tap! m :b)
      (harness/frames! m 16)
      (is (= 1 (teenager-hp m)))))
  (testing "too far away"
    (let [m (in-the-park! 20 :right)]
      (tap! m :b)
      (harness/frames! m 16)
      (is (= 3 (teenager-hp m)))))
  (testing "facing the other way"
    (let [m (in-the-park! 4 :left)]
      (tap! m :b)
      (harness/frames! m 16)
      (is (= 3 (teenager-hp m)))))
  (testing "three hits and he's gone: not drawn, and not in the way"
    (let [m (in-the-park! 4 :right)
          {:keys [x]} (first (:enemies (get (into {} world/rooms) :park)))
          oam-y (fn [m] (harness/peek-at m (+ (addr m :oam) (* 4 (count (art/pose-pieces :down 0))))))]
      (is (not= 0xFF (oam-y m)) "he's drawn")
      (dotimes [_ 3]
        (tap! m :b)
        (harness/frames! m 24))
      (is (zero? (teenager-hp m)))
      (harness/frames! m 25)
      (is (= 0xFF (oam-y m)) "his sprites are hidden")
      (walk! m :right 40)
      (is (> (first (where m)) (+ x 10)) "the player walks through where he stood"))))

(deftest the-swing-overlay-shows-the-box
  (let [draw (:draw (second poco/overlays))
        player {:x 100 :y 50}]
    (is (nil? (draw {:rt.state :room :player player :facing :right :swing 0})))
    (let [[[_ {:keys [x y w h]}]] (draw {:rt.state :room :player player :facing :right :swing 5})]
      (is (= [(+ 100 13) (+ 50 26) poco/swing-reach 6] [x y w h])))))

(deftest the-dad-can-be-talked-to
  (let [m (inside-the-house! (game))
        {:keys [x y]} (first (:npcs (get (into {} world/rooms) :house)))
        [px py] (where m)]
    (is (= [120 176] [px py]) "arrived at the door")
    (walk! m :up 30)                                ; level with him
    (let [[_ y1] (where m)]
      (is (< (- y 8) y1 (+ y 8)) "level with the dad"))
    (walk! m :left 80)                              ; ...and walk into him
    (let [[x1 _] (where m)]
      (is (> x1 (+ x 9)) "he's in the way: the player's feet stop before his")
      (is (< x1 (+ x 40)) "but only just"))
    (is (= :left (facing m)))
    (testing "A talks to him, and A closes the box"
      (tap! m :a)
      (harness/frames! m 3)
      (is (= :talk (state m)))
      (is (= (talk/number [:npc :house :dad]) (harness/peek-at m :message)))
      (tap! m :a)
      (harness/frames! m 3)
      (is (= :room (state m))))
    (testing "from further away, A does nothing"
      (walk! m :right 20)
      (harness/hold! m :left) (harness/frame! m) (harness/hold! m)
      (tap! m :a)
      (harness/frames! m 3)
      (is (= :room (state m))))))

(deftest an-item-lying-about-can-be-picked-up
  (let [m (inside-the-house! (game))
        stick-bit 1]
    (is (zero? (bit-and stick-bit (harness/peek-at m :items))) "no stick yet")
    ;; from (120, 176) walk up and right, to stand on it at (176, 96)
    (walk! m :right 38)
    (walk! m :up 66)
    (harness/frames! m 4)
    (is (= 1 (bit-and 1 (harness/peek-at m :items))) "the stick is the player's")
    (is (= :talk (state m)) "and the game says so")
    (is (= (talk/number [:found :stick]) (harness/peek-at m :message)))
    (tap! m :a)
    (harness/frames! m 3)
    (is (= :room (state m)) "A goes back to the game")
    (testing "it stays gone"
      (walk! m :down 20)
      (walk! m :up 20)
      (is (= 1 (bit-and 1 (harness/peek-at m :items)))))))

(deftest a-sign-can-be-read
  (let [m (game)]
    (tap! m :a)
    (harness/frames! m 3)
    (is (= :room (state m)) "A with nothing in front of you does nothing")
    (walk! m :right 6)
    (walk! m :down 40)
    (is (= :down (facing m)))
    (tap! m :a)
    (harness/frames! m 3)
    (is (= :talk (state m)) "facing the sign, A reads it")
    (is (= (talk/number [:sign :home]) (harness/peek-at m :message)))
    (let [at (where m)]
      (walk! m :up 10)
      (is (= at (where m)) "no walking while reading"))
    (tap! m :b)
    (harness/frames! m 3)
    (is (= :room (state m)))
    (is (= :home (room m)))))

(deftest swinging-the-stick
  (testing "without the stick, B does nothing"
    (let [m (game)]
      (tap! m :b)
      (is (zero? (pose m)))))
  (testing "with it, B swings: the two swing pictures in turn, standing still"
    (let [m (doto (game) (harness/poke-at! :items 2r1111))
          start (where m)]
      (harness/hold! m :b) (harness/frame! m)
      (harness/hold! m :right)
      (is (= 5 (pose m)) "the first picture")
      (let [seen (loop [n 0 seen []]       ; the rest of the swing: 11 frames
                   (if (= n 11)
                     seen
                     (do (harness/frame! m) (recur (inc n) (conj seen (pose m))))))]
        (is (= #{5 6} (set seen)))
        (is (= [5 6] (map first (partition-by identity seen))) "one picture, then the other"))
      (is (= start (where m)) "it didn't walk, though right was held")
      (harness/hold! m)
      (harness/frame! m)
      (is (zero? (pose m)) "standing again")
      (harness/hold! m :right)
      (harness/frames! m 4)
      (is (not= start (where m)) "walks again afterwards"))))

(deftest left-is-right-mirrored
  (is (= (dissoc (art/poses :right) :flip?) (dissoc (art/poses :left) :flip?)))
  (is (:flip? (art/poses :left)))
  (is (not (:flip? (art/poses :right)))))

(deftest every-message-fits
  (is (= (* 64 (count talk/messages)) (count talk/table))))

(deftest the-world-is-consistent
  (testing "every exit goes somewhere, and back"
    (let [exits (into {} (for [[name {:keys [exits]}] world/rooms] [name exits]))
          opposite {:left :right, :right :left, :up :down, :down :up}]
      (doseq [[name es] exits, [edge to] es]
        (is (= name (get-in exits [to (opposite edge)])) (str name " " edge " -> " to)))))
  (testing "every door goes somewhere, and back"
    (let [doors (into {} (for [[name {:keys [door]}] world/rooms :when door] [name door]))]
      (doseq [[name {:keys [to]}] doors]
        (is (= name (get-in doors [to :to])) (str name " -> " to))))))

(defn- blocked-at?
  "Are the player's feet at x y (which can be off the screen a little, as
  they're on the way out) in a wall, in `room`?"
  [room x y]
  (let [rows (:rows (get (into {} world/rooms) room))
        kind (fn [px py]
               (let [c (quot px 16) r (quot py 16)]
                 (if (or (neg? c) (neg? r) (> c 15) (> r 14))
                   :empty
                   (get-in art/metatiles [(get world/legend (get-in rows [r c])) :kind] :empty))))]
    (boolean (some (fn [[dx dy]] (#{:solid :sign} (kind (+ x dx) (+ y dy))))
                   [[3 26] [12 26] [3 31] [12 31]]))))

(deftest every-way-out-of-a-room-arrives-somewhere-free
  (doseq [[a {:keys [exits]}] world/rooms
          [edge b] exits
          v (range 0 240)
          :let [[x y] (case edge :right [240 v] :left [-1 v] :down [v 208] :up [v -12])
                [ax ay] (case edge :right [2 v] :left [222 v] :down [v 2] :up [v 200])]
          :when (not (blocked-at? a x y))]
    (when (blocked-at? b ax ay)
      (is false (str "leaving " a " " edge " at " v " arrives in a wall of " b)))))

(deftest the-top-of-a-room-is-a-wall-except-at-its-gap
  (let [m (game)]
    (harness/poke-at! m (inc (addr m :player/x)) 40)
    (walk! m :up 120)
    (is (= :home (room m)) "walking up in the trees")
    (is (let [y (second (where m))] (or (< y 20) (> y 240))) "...but right up to them (a little off the top of the screen)"))
  (let [m (game)]
    (harness/poke-at! m (inc (addr m :player/x)) 116)
    (walk-out! m :up 200)
    (harness/frames! m 3)
    (is (= :river (room m)) "through the gap")))

(deftest the-player-slides-round-a-corner
  (let [m (game)]
    ;; the house is the blocks at columns 3 to 6 of rows 4 and 5, from y = 64: walk
    ;; right at a height where the feet's bottom corner clips it by a few pixels
    (harness/poke-at! m (inc (addr m :player/x)) 30)
    (harness/poke-at! m (inc (addr m :player/y)) 36)
    (walk! m :right 40)
    (let [[x y] (where m)]
      (is (> x 60) "slid past instead of sticking")
      (is (<= y 33) "nudged up to clear it"))))

(defn- selected [m] (harness/peek-at m :selected))

(defn- open-items [m]
  (tap! m :start)
  (harness/frames! m 3)
  m)

(deftest select-goes-back-to-the-title
  (let [m (game)]
    (tap! m :select)
    (harness/frames! m 3)
    (is (= :title (state m)))))

(deftest the-player-starts-with-the-starting-items
  (let [m (game)]
    (is (= 2r1110 (harness/peek-at m :items)) "all but the stick")))

(deftest start-opens-the-items-screen-and-closes-it-again
  (let [m (game)
        at (where m)]
    (open-items m)
    (is (= :items (state m)))
    (tap! m :start)
    (harness/frames! m 3)
    (is (= at (where m)) "the player's still where they were")
    (open-items m)
    (tap! m :b)
    (harness/frames! m 3)
    (is (= :room (state m)) "B closes it too")))

(deftest the-cursor-moves-round-the-slots
  (let [m (open-items (game))]
    (is (zero? (selected m)))
    (tap! m :right) (is (= 1 (selected m)))
    (tap! m :right) (tap! m :right) (is (= 3 (selected m)))
    (tap! m :right) (is (zero? (selected m)) "round the right side to the left")
    (tap! m :left) (is (= 3 (selected m)) "and back")
    (tap! m :down) (is (= 7 (selected m)) "the second row")
    (tap! m :up) (is (= 3 (selected m)))
    (tap! m :up) (is (= 7 (selected m)) "two rows, so up wraps round too")))

(deftest the-cursor-is-drawn-round-the-selected-slot
  (let [m (open-items (game))
        oam (addr m :oam)
        corner #(mapv (fn [k] (harness/peek-at m (+ oam (* 4 %) k))) [0 3])]
    (harness/frames! m 2)                 ; (it blinks: this is in the on half)
    (is (= (let [[x y] (menu/slot-pixels 0)] [y x]) [(first (corner 0)) (second (corner 0))])
        "its top left corner is at the slot's top left")
    (tap! m :right)
    (harness/frames! m 2)
    (is (= (first (menu/slot-pixels 1)) (second (corner 0))))))

(defn- queued-line
  "The tiles of the line of words the game last queued, from `from` in the
  queue: [address-high address-low count tile...]."
  [m from]
  (let [q (addr m :video-queue)
        at #(harness/peek-at m (+ q %))
        n (at (+ from 2))]
    {:address (+ (* 256 (at from)) (at (inc from)))
     :tiles (mapv #(at (+ from 3 %)) (range n))}))

(defn- spelled [words]
  (menu/line words))

(deftest the-words-are-for-the-selected-item
  (let [m (doto (game) (harness/poke-at! :items 2r1111))
        _ (open-items m)]
    (testing "the first item's name and what it says, in the two rows"
      (is (= (spelled "STICK") (:tiles (queued-line m 0))))
      (is (= (spelled "FOR POKING THINGS.") (:tiles (queued-line m 31)))))
    (tap! m :right)
    (is (= (spelled "BASEBALL BAT") (:tiles (queued-line m 0))))
    (is (= (spelled "SWING IT AT TEENAGERS.") (:tiles (queued-line m 31))))
    (testing "the words go to the right place on the screen"
      (is (= 0x2282 (:address (queued-line m 0))) "tile row 20, column 2 is $2000 + 20*32 + 2")
      (is (= 0x22C2 (:address (queued-line m 31)))))
    (testing "a slot with no item says ?????"
      (tap! m :down)
      (is (= (spelled "?????") (:tiles (queued-line m 0))))
      (is (= (spelled "") (:tiles (queued-line m 31)))))))

(deftest only-items-the-player-has-have-words
  (let [m (open-items (game))]
    (harness/poke-at! m :items 2r1101)    ; no bat
    (tap! m :right)
    (is (= (spelled "?????") (:tiles (queued-line m 0))) "the bat isn't there")
    (tap! m :right)
    (is (= (spelled "PIZZA SLICE") (:tiles (queued-line m 0))))))

(defn- teenager-at [m]
  [(harness/peek-at m (addr m :enemy-x)) (harness/peek-at m (addr m :enemy-y))])

(defn- block-at
  "The character in the park's map under the pixel [x y]."
  [[x y]]
  (get-in (vec (:rows (get (into {} world/rooms) :park))) [(quot y 16) (quot x 16)]))

(deftest the-teenager-wanders
  (let [m (doto (game) (harness/poke-at! :items 2r1111))
        start (first (:enemies (get (into {} world/rooms) :park)))]
    (is (= [(:x start) (:y start)] (teenager-at m)) "he starts where the room puts him")
    (walk-out! m :right 300)
    (harness/frames! m 3)
    (is (= :park (room m)))
    (harness/poke-at! m (inc (addr m :player/x)) 8)       ; out of his way, by the left gap
    (harness/poke-at! m (inc (addr m :player/y)) 100)
    (testing "he moves, and keeps to the floor"
      (let [seen (atom #{})
            {:keys [left top right bottom]} poco/feet]
        (dotimes [_ 1500]
          (harness/frame! m)
          (let [[x y :as at] (teenager-at m)]
            (swap! seen conj at)
            (doseq [[dx dy] [[left top] [right top] [left bottom] [right bottom]]]
              (is (not (#{\T \~ \S} (block-at [(+ x dx) (+ y dy)]))) (str "in a wall at " at)))))
        (is (> (count @seen) 100) "he covers some ground")
        (is (> (count (set (map first @seen))) 20))
        (is (> (count (set (map second @seen))) 20))))))

(deftest the-teenager-is-stunned-when-hit
  (let [m (in-the-park! 4 :right)
        before (teenager-at m)]
    (tap! m :b)
    (harness/frames! m 14)
    (is (= 2 (teenager-hp m)))
    (let [stunned (set (for [_ (range 10)] (do (harness/frame! m) (teenager-at m))))]
      (is (= 1 (count stunned)) "he doesn't move while he flickers"))
    (is (some? before))))

(deftest the-teenager-stays-in-his-room
  (let [m (game)
        before (teenager-at m)]
    (harness/frames! m 300)
    (is (= :home (room m)))
    (is (= before (teenager-at m)) "nobody's there to see him")))

(deftest the-teenager-doesnt-walk-through-the-player
  (let [m (doto (game) (harness/poke-at! :items 2r1111))
        [tx ty] (do (walk-out! m :right 300) (harness/frames! m 3) (teenager-at m))]
    (harness/poke-at! m (inc (addr m :player/x)) (- tx 11))
    (harness/poke-at! m (inc (addr m :player/y)) ty)
    (dotimes [_ 600]
      (harness/frame! m)
      (let [[x y] (teenager-at m)
            [px py] (where m)]
        (is (not (and (<= (Math/abs (- x px)) 9) (<= (Math/abs (- y py)) 5)))
            (str "feet apart: " [x y] [px py]))))))

(defn- near-the-teenager!
  "A game in the park, the player (30 pixels left, 10 up of the teenager),
  and the teenager doing `dir` (see :enemy-dir) for 60 frames."
  [dir]
  (let [m (doto (game) (harness/poke-at! :items 2r1111))
        [tx ty] (do (walk-out! m :right 300) (harness/frames! m 3) (teenager-at m))]
    (harness/poke-at! m (inc (addr m :player/x)) (- tx 30))
    (harness/poke-at! m (inc (addr m :player/y)) (- ty 10))
    (harness/poke-at! m (addr m :enemy-dir) dir)
    (harness/poke-at! m (addr m :enemy-timer) 60)
    m))

(defn- distance-between [m]
  (let [[tx ty] (teenager-at m) [px py] (where m)]
    (+ (Math/abs (- tx px)) (Math/abs (- ty py)))))

(deftest the-teenager-chases-and-flees
  (testing "chasing, he closes in (but never gets inside the player)"
    (let [m (near-the-teenager! 5)
          before (distance-between m)]
      (harness/frames! m 20)
      (is (< (distance-between m) (- before 10)))))
  (testing "fleeing, he gets away"
    (let [m (near-the-teenager! 6)
          before (distance-between m)]
      (harness/frames! m 20)
      (is (> (distance-between m) (+ before 10)))))
  (testing "when the player's close he thinks again and does one or the other; far away he wanders"
    (let [m (near-the-teenager! 4)]
      (harness/poke-at! m (addr m :enemy-timer) 0)
      (harness/frame! m)
      (is (#{5 6} (harness/peek-at m (addr m :enemy-dir)))))
    (let [m (near-the-teenager! 4)]
      (harness/poke-at! m (inc (addr m :player/x)) 8)
      (harness/poke-at! m (addr m :enemy-timer) 0)
      (harness/frame! m)
      (is (< (harness/peek-at m (addr m :enemy-dir)) 5)))))

(deftest sprites-and-background-have-their-own-tiles
  (let [chr (get-in (poco/build) [:source :chr])
        tile (fn [table n] (vec (take 16 (drop (+ (* 4096 table) (* 16 n)) chr))))
        sprite-tiles (:tiles art/sprite-tiles)]
    (is (= (vec chr/blank) (tile 0 0)) "the background's tile 0 is blank")
    (is (= (vec (first (:tiles art/tiles))) (tile 0 0)))
    (is (= (vec (second sprite-tiles)) (tile 1 1)) "the sprites' tiles are in the second table")
    (is (= (vec (nth (:tiles art/tiles) 5)) (tile 0 5)))
    (is (<= (count (:tiles art/tiles)) 256))
    (is (<= (count sprite-tiles) 256))))

(deftest the-teenager-explodes
  (let [m (in-the-park! 4 :right)
        boom (fn [m] (harness/peek-at m (addr m :enemy-boom)))
        sprites-drawn (fn [m]                 ; sprites in the oam that aren't hidden
                        (count (filter #(not= 0xFF (harness/peek-at m (+ (addr m :oam) (* 4 %)))) (range 64))))]
    (dotimes [_ 2] (tap! m :b) (harness/frames! m 24))
    (is (= 1 (teenager-hp m)))
    (is (zero? (boom m)) "not yet")
    (tap! m :b)
    (harness/frames! m 7)                     ; the stick comes down
    (is (zero? (teenager-hp m)))
    (is (pos? (boom m)) "out of hits: exploding")
    (let [seen (atom #{})]
      (dotimes [_ 20]
        (harness/frame! m)
        (swap! seen conj (boom m)))
      (is (contains? @seen 0) "it ends")
      (is (> (count @seen) 10) "it counts down"))
    (harness/frames! m 5)
    (is (zero? (boom m)))
    (is (= 0 (teenager-hp m)))
    (testing "the sprites of the explosion are drawn while it lasts, and gone after"
      (let [m (in-the-park! 4 :right)]
        (dotimes [_ 2] (tap! m :b) (harness/frames! m 24))
        (tap! m :b)
        (harness/frames! m 8)
        (let [during (sprites-drawn m)]
          (harness/frames! m 30)
          (is (< (sprites-drawn m) during)))))))
