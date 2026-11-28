(ns poco.core-test
  "Plays The Legend of PoCo on the ClojureScript CPU by itself (see
  zeropage.harness): walking, the walk cycle, the walls, and the doors
  between rooms."
  (:require [cljs.test :refer [deftest is testing]]
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
  (is (= #{:teenager} (art/guests-of :teenager)) "the teenager's colors aren't the player's")
  (is (= #{} (art/guests-of :dad)) "the dad is drawn in the player's, for now")
  (is (<= (+ art/guest-slot (count (first (vals art/guest-palettes)))) 4) "there's a slot for it")
  (testing "the teenager's pictures use slot 3 (the guest's) or the player's"
    (let [palettes-used (set (for [[_ _ _ p] (art/character-pieces :teenager)] p))]
      (is (contains? palettes-used art/guest-slot))
      (is (every? #(< % 4) palettes-used))))
  (testing "no room has two guests"
    (doseq [[room {:keys [npcs enemies]}] world/rooms]
      (is (<= (count (distinct (mapcat #(art/guests-of (:kind %)) (concat npcs enemies)))) 1) (str room)))))

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
