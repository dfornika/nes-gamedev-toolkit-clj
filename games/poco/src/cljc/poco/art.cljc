(ns poco.art
  "How The Legend of PoCo looks: every picture, and the colors they're drawn in.

  The player comes from the PNGs in resources/assets/sprites/player (drawn in
  Aseprite; `clojure -T:build import` turns them into player.edn, see
  poco.import). The background (the ground, trees, doors, signs...) is in
  resources/assets/tiles.edn, drawn in a tilesheet (a PNG: `clojure -T:build
  tiles` turns the cells it uses into sheets.edn), or as text, one character
  a pixel: . is see-through (or the backdrop), and 1 2 3 are the palette's
  three colors; the colors themselves are in palettes.edn. This file reads and checks
  them (see cartridj.assets), and puts it all together.

  Change a drawing or a color, then `(play! (poco.core/build))` at the REPL."
  (:require [cartridj.assets :as assets]
            [cartridj.chr :as chr]
            [cartridj.font :as font]
            [cartridj.palette :as palette]
            [cartridj.tileset :as tileset]
            [poco.items :as items]
            [poco.util :as util]))

;; ---------------------------------------------------------------------------
;; The player

(def art
  "What the importer made from the PNGs: {:palettes :tiles :frames}.
  GENERATED: change the pictures, not this."
  (util/inline-edn-resource "assets/sprites/player/player.edn"))

(def poses
  "Which picture is which: {direction {:stand picture :walk [four pictures]}},
  from resources/assets/sprites/player/poses.edn (which says how a picture
  is named, and how left is drawn as right). A direction that's a :mirror
  of another gets that one's pictures and :flip? true."
  (let [data (util/inline-edn-resource "assets/sprites/player/poses.edn")]
    (into {} (for [[direction {:keys [mirror] :as pose}] data]
               [direction (if mirror
                            (assoc (or (data mirror)
                                       (throw (ex-info (str "poses.edn: " (pr-str direction) " mirrors "
                                                            (pr-str mirror) ", which isn't there") {})))
                                   :flip? true)
                            pose)]))))

(def directions
  "The numbers the game keeps for the way the player faces."
  [:down :up :left :right])

(defn- width
  "How wide a picture made of `pieces` is, in pixels."
  [pieces]
  (+ 8 (apply max (map second pieces))))

(def frames
  "{name pieces} for the imported pictures: each piece is [tile dx dy
  palette]."
  (into {} (map (juxt :name :sprites)) (:frames art)))

(def steps
  "How many pictures a direction has: standing (0), the walk's four (1 to
  4), then the swing's (5 on)."
  (let [{:keys [walk swing]} (:down poses)]
    (+ 1 (count walk) (count swing))))

(defn missing
  "The pictures `poses` names that haven't been imported."
  []
  (->> (vals poses)
       (mapcat (fn [{:keys [stand walk swing]}] (concat [stand] walk swing)))
       (mapcat #(if (vector? %) % [%]))
       distinct
       (remove frames)
       sort))

(defn flip-pieces
  "`pieces` ([tile dx dy palette] ...) as if seen in a mirror: each piece
  the other side of the middle of a picture that's `width` pixels wide,
  and flipped left to right."
  [pieces width]
  (vec (for [[tile dx dy attributes] pieces]
         [tile (- width 8 dx) dy (bit-or (or attributes 0) 0x40)])))

(defn pieces
  "What to draw for `picture` (a name or [top bottom] from `poses`), mirrored if `flip?`."
  [picture flip?]
  (let [frame (fn [name]
                (or (frames name)
                    (:sprites (first (:frames art)))
                    (throw (ex-info "No player pictures at all: run clojure -T:build import" {}))))
        found (if (vector? picture)
                (let [[top bottom] picture]
                  (vec (concat (filter #(< (nth % 2) 16) (frame top))
                               (remove #(< (nth % 2) 16) (frame bottom)))))
                (frame picture))]
    (if flip?
      (flip-pieces found (width found))
      found)))

(defn pose-pieces
  "The pieces for a direction and a step: 0 is standing, 1 to 4 the walk
  cycle, and 5 on the swing of the stick. A picture that hasn't been
  imported yet is drawn as the direction's standing one."
  [direction step]
  (let [{:keys [stand walk swing flip?]} (poses direction)
        picture (nth (concat [stand] walk swing) step)
        have? (every? frames (if (vector? picture) picture [picture]))]
    (pieces (if have? picture stand) flip?)))

;; ---------------------------------------------------------------------------
;; The background

(def background
  "The background's drawings, checked (see cartridj.assets):
  {:backdrop :palettes :metatiles}. In resources/assets/tiles.edn."
  (let [data (util/inline-edn-resource "assets/tiles.edn")
        sheets (util/inline-edn-resource "assets/sheets.edn")
        colors (assets/palettes (util/inline-edn-resource "assets/palettes.edn") "palettes.edn")]
    (assoc data
           :colors colors
           :metatiles (-> (:metatiles data)
                          (assets/with-sources sheets "tiles.edn")
                          (assets/drawings colors "tiles.edn")))))

(def box-font
  "The font on a solid background, for the text box: the letters are color
  1 and the background color 3, so they stand out in the box whatever's
  behind it (a tile of the world's has no see-through: that's the backdrop
  color)."
  (mapv (fn [glyph]
          (into (vec (repeat 8 0xFF))                                  ; plane 0: all on
                (map #(bit-xor % 0xFF) (take 8 glyph))))              ; plane 1: off for the letters
        font/tiles))

(def cursor-look
  "How the items screen's cursor is drawn: a sprite palette that has white
  in it (the importer picked the palettes, so look), and which of that
  palette's colors it is."
  (or (first (for [[i p] (map-indexed vector (:palettes art))
                   [c color] (map-indexed vector p)
                   :when (= color 0x30)]
               {:palette i :digit (inc c)}))
      {:palette 0 :digit 3}))

(def cursor
  "One corner of the cursor, 8x8: the box round an item on the items screen
  is four of these, flipped."
  (let [d (str (:digit cursor-look))]
    (chr/tile [(str d d d d d "...")
               (str d "...." "...")
               (str d ".......")
               (str d ".......")
               (str d ".......")
               "........" "........" "........"])))

(def tiles
  "Everything in the cartridge's graphics, with names (see
  cartridj.tileset). Tile 0 is blank, and the player's come next: the
  importer numbers them from 1. Then the background's tiles, in the order
  of tiles.edn."
  (tileset/tileset (into (into [[:blank  [chr/blank]]
                                [:player (map chr/tile (:tiles art))]]
                               (for [{:keys [id pixels]} (:metatiles background)]
                                 [id pixels]))
                         (into [[:slot    items/slot]
                                [:cursor  [cursor]]
                                [:font    font/tiles]
                                [:box-font box-font]]
                               ;; each item's picture: 2x2 tiles
                               (for [{:keys [id drawing]} items/items]
                                 [(keyword "item" (name id)) drawing])))))

(def metatiles
  "The 16x16 blocks that backgrounds are made of: four tiles, and which of
  the four background palettes (`palettes`) colors them. :kind says what
  it's like to walk into: :solid blocks the player, :empty doesn't."
  (let [palette-numbers (into {} (map-indexed (fn [i name] [name i])) (:palettes background))]
    (into {} (for [{:keys [id palette kind]} (:metatiles background)]
               [id (cond-> {:tiles (tileset/block tiles id)
                            :palette (or (palette-numbers palette)
                                         (throw (ex-info (str "tiles.edn: " (pr-str id) " is in the palette " (pr-str palette)
                                                              ", which isn't one of the room's: " (pr-str (:palettes background)))
                                                         {})))}
                     kind (assoc :kind kind))]))))

;; ---------------------------------------------------------------------------
;; Colors

(def backdrop
  "The color behind everything (the grass shows it through)."
  (palette/color (:backdrop background)))

(def palettes
  "Four background palettes (each three colors besides the backdrop) and
  four for sprites, which are the player's: the importer found them in the
  pictures. Colors are names (see cartridj.palette/colors) or numbers."
  (palette/palettes
   {:backdrop   backdrop
    :background (mapv (:colors background) (:palettes background))
    :sprites    (:palettes art)}))
