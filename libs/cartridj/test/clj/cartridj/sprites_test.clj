(ns cartridj.sprites-test
  "The sprite importer, on small pictures made here. JVM only: it reads PNGs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cartridj.palette :as palette]
            [cartridj.sprites :as sprites])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- rgb-of
  "The color of NES color `n` in the Aseprite table, as 0xAARRGGBB."
  [n]
  (let [[r g b] (palette/rgb-palette-aseprite n)]
    (unchecked-int (bit-or 0xFF000000 (bit-shift-left r 16) (bit-shift-left g 8) b))))

(defn- picture!
  "Write a PNG into `dir`: `rows` are strings, each character a NES color
  from `colors` ({char number}); anything else is see-through."
  [dir file colors rows]
  (let [w (count (first rows)) h (count rows)
        image (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)]
    (doseq [[y row] (map-indexed vector rows)
            [x c] (map-indexed vector row)]
      (.setRGB image x y (if-let [n (colors c)] (rgb-of n) 0)))
    (ImageIO/write image "png" (io/file dir file))))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "sprites-test" (make-array FileAttribute 0))))

(def colors {\a 0x16 \b 0x27 \c 0x30 \d 0x0F})

(def two-tiles
  ;; 16x8: skin-ish and red on the left, white on the right
  ["aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."])

(deftest a-picture-becomes-tiles-and-sprites
  (let [dir (temp-dir)
        out (io/file dir "art.edn")]
    (picture! dir "one.png" colors two-tiles)
    (let [art (sprites/import! dir out :first-tile 1)]
      (is (= [[0x16 0x27 0x30]] (map #(vec (sort %)) (:palettes art))) "three colors fit in one palette")
      (is (= 2 (count (:tiles art))))
      (is (= :one (:name (first (:frames art)))))
      (is (= [[1 0 0 0] [2 8 0 0]] (:sprites (first (:frames art))))
          "tile numbers start at :first-tile; x and y are in pixels")
      (is (= art (read-string (slurp out))) "what's written reads back the same"))))

(deftest identical-tiles-are-shared-and-empty-ones-left-out
  (let [dir (temp-dir)]
    (picture! dir "a.png" colors
              ["aaaa....aaaa...." "aaaa....aaaa...." "aaaa....aaaa...." "aaaa....aaaa...."
               "aaaa....aaaa...." "aaaa....aaaa...." "aaaa....aaaa...." "aaaa....aaaa...."])
    (let [art (sprites/build [(sprites/read-frame (io/file dir "a.png"))] {})]
      (is (= 1 (count (:tiles art))) "two identical tiles are one")
      (is (= 2 (count (:sprites (first (:frames art))))) "and drawn twice")
      (is (= [[0 0 0 0] [0 8 0 0]] (:sprites (first (:frames art))))))))

(deftest black-is-a-color-not-see-through
  (let [dir (temp-dir)]
    (picture! dir "b.png" colors (concat ["ddaa...."] (repeat 7 "........")))
    (let [art (sprites/build [(sprites/read-frame (io/file dir "b.png"))] {})]
      (is (= [[0x0F 0x16]] (:palettes art)) "black is in the palette, darkest first")
      (is (= "1122...." (first (first (:tiles art)))) "black is 1, the other color 2, see-through is ."))))

(deftest a-tile-with-four-colors-is-an-error-that-says-where
  (let [dir (temp-dir)]
    (picture! dir "four.png" colors
              ["abcd...." "........" "........" "........" "........" "........" "........" "........"])
    (let [e (try (sprites/build [(sprites/read-frame (io/file dir "four.png"))] {})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (str/includes? (ex-message e) "four: the tile at row 0, column 0"))
      (is (str/includes? (ex-message e) "uses 4 colors"))
      (is (str/includes? (ex-message e) "$0F $16 $27 $30")))))

(deftest a-color-not-in-the-table-is-an-error-unless-nearest
  (let [dir (temp-dir)
        image (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB)]
    (doseq [y (range 8) x (range 8)] (.setRGB image x y (unchecked-int 0xFF123456)))
    (ImageIO/write image "png" (io/file dir "odd.png"))
    (let [frame (sprites/read-frame (io/file dir "odd.png"))]
      (is (some #(str/includes? % "rgb(18 52 86)") (:problems frame)))
      (is (empty? (:problems (sprites/read-frame (io/file dir "odd.png") {:nearest? true})))))))

(deftest more-than-four-palettes-is-an-error
  (let [dir (temp-dir)
        ;; five tiles, each with its own three colors
        trios [[0x16 0x27 0x30] [0x12 0x22 0x32] [0x18 0x28 0x38] [0x1A 0x2A 0x3A] [0x14 0x24 0x34]]
        cs (into {} (map-indexed (fn [i n] [(char (+ 97 i)) n]) (distinct (apply concat trios))))
        ch (fn [n] (key (first (filter #(= n (val %)) cs))))
        row (fn [trio] (apply str (concat (map ch trio) (repeat 5 \.))))]
    (picture! dir "many.png" cs (apply concat (for [trio trios] (cons (row trio) (repeat 7 "........")))))
    (let [e (try (sprites/build [(sprites/read-frame (io/file dir "many.png"))] {})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (str/includes? (ex-message e) "5 sprite palettes")))))

(deftest given-palettes-are-used
  (let [dir (temp-dir)]
    (picture! dir "p.png" colors two-tiles)
    (let [art (sprites/build [(sprites/read-frame (io/file dir "p.png"))] {:palettes [[0x30] [0x27 0x16 0x0F]]})]
      (is (= [[0x30] [0x27 0x16 0x0F]] (:palettes art)))
      (is (= [1 0] (map #(nth % 3) (:sprites (first (:frames art)))))
          "tiles go to the first palette that has their colors"))))

(deftest guests-get-palettes-of-their-own-only-for-what-the-shared-ones-lack
  (let [dir (temp-dir)]
    ;; the hero's one tile uses a b c, so that's the shared palette
    (picture! dir "hero-01.png" colors ["aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.."])
    ;; the imp's first tile uses a and b (fits the shared palette), its second c and d (doesn't)
    (picture! dir "imp-01.png" colors (vec (repeat 8 "aabb....ccdd....")))
    (let [art (sprites/build (mapv #(sprites/read-frame (io/file dir %)) ["hero-01.png" "imp-01.png"])
                             {:shared #{:hero} :first-tile 1})
          imp (second (:frames art))]
      (is (= [[0x16 0x27 0x30]] (map #(vec (sort %)) (:palettes art))) "just the hero's")
      (is (= {:imp [[0x0F 0x30]]} (update-vals (:guests art) #(mapv (comp vec (partial sort)) %))) "only c and d, which the imp needs besides")
      (is (= [:hero :imp] (map :character (:frames art))))
      (is (= [0 1] (map #(nth % 3) (:sprites imp))) "the first tile uses the shared palette (0), the second the imp's (slot 1)"))))

(deftest a-guest-that-doesnt-fit-in-the-slots-left-is-an-error
  (let [dir (temp-dir)]
    (picture! dir "hero-01.png" colors ["aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.." "aabbcc.."])
    (picture! dir "imp-01.png" colors (vec (repeat 8 "ddaa....")))
    (let [frames (mapv #(sprites/read-frame (io/file dir %)) ["hero-01.png" "imp-01.png"])
          error (try (sprites/build frames {:shared #{:hero} :max-palettes 1}) nil
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (some? error))
      (is (str/includes? error "imp needs 1 sprite palette(s) of its own")))))
