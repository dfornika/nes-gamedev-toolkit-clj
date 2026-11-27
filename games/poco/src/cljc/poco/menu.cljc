(ns poco.menu
  "The items screen, laid out: a grid of eight slots, two rows of four, with
  what the selected one is called and does written underneath.

  The background is a screen like the rooms (see poco.world), with an empty
  frame in each slot. What the player has isn't known until the game runs,
  so the picture of each item they have is drawn over its frame when the
  screen is opened (see poco.core). The words under the picture are the same
  kind of thing: a table of tiles in the ROM, a line for each slot,
  copied to the background when the cursor moves."
  (:require [cartridj.font :as font]
            [cartridj.palette :as palette]
            [cartridj.screen :as screen]
            [cartridj.tileset :as tileset]
            [poco.art :as art]
            [poco.items :as items]))

(def columns
  "The block (16x16, the screen is 16 across and 15 down) each column of slots is in."
  [3 6 9 12])

(def rows
  "...and each row of slots."
  [4 7])

(def text-columns 28)
(def name-row "The tile row (30 down the screen) of the item's name" 20)
(def doc-row "...and of what it says" 22)
(def text-column 2)

(defn slot-block
  "[block-column block-row] of slot number `i`."
  [i]
  [(columns (mod i 4)) (rows (quot i 4))])

(defn slot-pixels
  "[x y] in pixels of the top left of slot `i`."
  [i]
  (let [[c r] (slot-block i)] [(* 16 c) (* 16 r)]))

(def palettes
  "The 16 bytes of background palettes for the items screen."
  (vec (take 16 (palette/palettes items/palettes))))

(defn- item-in [i] (nth items/items i nil))

(def screen-data
  (let [slot-tiles (tileset/block art/tiles :slot)
        blocks (into {:blank {:tiles [0 0 0 0] :palette 0}}
                     (for [i (range items/slots)]
                       [(keyword (str "slot-" i))
                        {:tiles slot-tiles :palette (:palette (item-in i) 0)}]))
        at (into {} (for [i (range items/slots)] [(slot-block i) i]))
        rows' (vec (for [r (range screen/rows)]
                     (apply str (for [c (range screen/columns)]
                                  (if-let [i (at [c r])] (char (+ (int \0) i)) \.)))))]
    (screen/screen {:metatiles blocks
                    :legend (into {\. :blank} (for [i (range items/slots)] [(char (+ (int \0) i)) (keyword (str "slot-" i))]))
                    :rows rows'
                    :font-base (tileset/tile art/tiles :font)
                    :text [[13 4 "ITEMS"]
                           [4 17 "START: BACK TO THE GAME"]]})))

(defn line
  "`words` as `text-columns` tile numbers, padded with spaces."
  [words]
  (when (> (count words) text-columns)
    (throw (ex-info (str (pr-str words) " is longer than " text-columns " letters") {})))
  (font/encode (apply str words (repeat (- text-columns (count words)) \space)) (tileset/tile art/tiles :font)))

(defn- entry
  "A slot's 64 bytes in the table: its name, 4 spare bytes (a line is 32
  tiles wide), then what it says; both padded."
  [{:keys [name doc]}]
  (vec (concat (line name) (repeat (- 32 text-columns) 0)
               (line doc) (repeat (- 32 text-columns) 0))))

(def unknown
  "What a slot says when the player doesn't have its item."
  (entry {:name "?????" :doc ""}))

(def text-table
  "Every slot's words, 64 bytes each: eight of them, the unknown last."
  (vec (mapcat #(entry (or (item-in %) {:name "?????" :doc ""})) (range items/slots))))

(def bit-of
  "The bit that says the player has slot `i`'s item."
  (vec (for [i (range items/slots)] (bit-shift-left 1 i))))

(def starting-bits
  (reduce bit-or 0 (for [[i {:keys [id]}] (map-indexed vector items/items)
                         :when (items/starting id)]
                     (bit-shift-left 1 i))))
