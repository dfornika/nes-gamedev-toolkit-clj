(ns poco.core-test
  "Plays The Legend of PoCo on the ClojureScript CPU by itself (see
  zeropage.harness): walking, the walk cycle, the walls, and the doors
  between rooms."
  (:require [cljs.test :refer [deftest is testing]]
            [poco.art :as art]
            [poco.core :as poco]
            [poco.world :as world]
            [zeropage.harness :as harness]))

(defn- addr [m name] (get (:symbols m) name))
(defn- whole [m name] (harness/peek-at m (inc (addr m name))))
(defn- where [m] [(whole m :player/x) (whole m :player/y)])
(defn- room [m] (nth world/room-names (harness/peek-at m :room)))
(defn- facing [m] (nth art/directions (harness/peek-at m :facing)))
(defn- pose [m] (harness/peek-at m :pose))

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

(deftest the-world-is-consistent
  (testing "every exit goes somewhere, and back"
    (let [exits (into {} (for [[name {:keys [exits]}] world/rooms] [name exits]))
          opposite {:left :right, :right :left, :up :down, :down :up}]
      (doseq [[name es] exits, [edge to] es]
        (is (= name (get-in exits [to (opposite edge)])) (str name " " edge " -> " to))))))

(deftest start-goes-back-to-the-title
  (let [m (game)]
    (tap! m :start)
    (harness/frames! m 3)
    (is (zero? (harness/peek-at m :rt/state)) "the title is state 0")))
