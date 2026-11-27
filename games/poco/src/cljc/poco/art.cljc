(ns poco.art
  "How The Legend of PoCo looks: every picture, and the colors they're drawn in.

  The player comes from the PNGs in resources/assets/sprites (drawn in
  Aseprite; `clojure -T:build import` turns them into players.edn, see
  poco.import). The background tiles are drawn right here as text, one
  character a pixel: . is see-through (or the backdrop), and 1 2 3 are the
  palette's three colors.

  Change a drawing or a color, then `(play! (poco.core/build))` at the REPL."
  (:require [cartridj.chr :as chr]
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
  (util/inline-edn-resource "assets/sprites/players.edn"))

(def poses
  "Which picture is which. Each direction the player can face has a
  :stand picture and a :walk cycle of four. A picture is a PNG's name
  (:players-03 is the file players-03.png), or [top bottom]: the top half
  (the head and shoulders, the first 16 pixels down) of one PNG with the
  bottom half (body and legs) of another. So a walking picture doesn't
  need its own head. A name that isn't imported yet (see `missing`) is
  drawn as the first picture that is, so you can fill these in as you
  draw them.

  The PNGs: 01 stands facing us; 02 and 03 are steps facing right, and
  04 stands facing right; 05 stands facing away, and 06 and 07 are steps
  facing away.

  :flip? draws the picture mirrored: left is drawn as right, backwards,
  so side-on art is only needed for one side. Facing us, the steps are
  made from our head with the legs of the steps facing away."
  (let [front-stand :players-01
        front-walk  [[:players-01 :players-06] front-stand [:players-01 :players-07] front-stand]
        back-stand  :players-05
        back-walk   [:players-06 back-stand :players-07 back-stand]
        right-stand :players-04
        right-walk  [:players-02 right-stand :players-03 right-stand]]
    {:down  {:stand front-stand :walk front-walk}
     :up    {:stand back-stand :walk back-walk}
     :right {:stand right-stand :walk right-walk}
     :left  {:stand right-stand :walk right-walk :flip? true}}))

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

(defn missing
  "The pictures `poses` names that haven't been imported."
  []
  (->> (vals poses)
       (mapcat (fn [{:keys [stand walk]}] (cons stand walk)))
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
  "The pieces for a direction and a step of the walk: step 0 is standing,
  and 1 to 4 are the walk cycle."
  [direction step]
  (let [{:keys [stand walk flip?]} (poses direction)]
    (pieces (if (zero? step) stand (nth walk (dec step))) flip?)))

;; ---------------------------------------------------------------------------
;; The background

(def grass
  ["................"
   "................"
   "....1...........",
   "...1............"
   "................"
   "..........1....."
   ".........1......"
   "................"
   "................"
   "......1........."
   ".....1.........."
   "................"
   "..........1....."
   ".........1......"
   "................"
   "................"])

(def flowers
  ["................"
   "..33............"
   ".3223...........",
   "..33.....33....."
   "...1....3223...."
   "...1.....33....."
   "....1....1......"
   "..........1....."
   "................"
   "......33........"
   ".....3223......."
   "......33........"
   ".......1........"
   ".......1........"
   "................"
   "................"])

(def tree
  ["................"
   "......1111......"
   "....11222211...."
   "...1222222221..."
   "..122222222221.."
   "..122322222221.."
   ".12233222222221."
   ".12222222232221."
   ".12222222222221."
   "..122222222221.."
   "...1222222221..."
   "....11222211...."
   ".......33......."
   ".......33......."
   "......3333......"
   "......3333......"])

(def water
  ["2222222222222222"
   "2222222222222222"
   "2221122222222222"
   "2222211222221122"
   "2222222222222222"
   "2222222222222222"
   "2222222211222222"
   "1122222222112222"
   "2222222222222222"
   "2222222222222222"
   "2222111222222222"
   "2222222221122222"
   "2222222222222222"
   "2112222222222222"
   "2222222222211222"
   "2222222222222222"])

(def path
  ["1111111111111111"
   "1111111111111111"
   "1111211111111111"
   "1111111111121111"
   "1111111111111111"
   "1211111111111111"
   "1111111111111211"
   "1111111111111111"
   "1111111111111111"
   "1111112111111111"
   "1111111111111111"
   "1121111111111111"
   "1111111111112111"
   "1111111111111111"
   "1111111211111111"
   "1111111111111111"])

(def brick
  ["3333333333333333"
   "1111111311111113"
   "1111111311111113"
   "1111111311111113"
   "3333333333333333"
   "1311111111311111"
   "1311111111311111"
   "1311111111311111"
   "3333333333333333"
   "1111111311111113"
   "1111111311111113"
   "1111111311111113"
   "3333333333333333"
   "1311111111311111"
   "1311111111311111"
   "1311111111311111"])

(def door
  ["3333333333333333"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222211223"
   "3222222222211223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"
   "3222222222222223"])

(def floor
  ["1111111111111111"
   "1111111111111111"
   "1111111111111111"
   "2222222222222222"
   "1111111211111111"
   "1111111211111111"
   "1111111211111111"
   "2222222222222222"
   "1111111111111211"
   "1111111111111211"
   "1111111111111211"
   "2222222222222222"
   "1111121111111111"
   "1111121111111111"
   "1111121111111111"
   "2222222222222222"])

(def stick-on-floor
  "The stick (poco.items), lying on the floor: its see-through pixels are
  the floor's. (A background tile has no see-through: it's the backdrop,
  which in the world is the grass color. The stick's colors are the
  floor's, palette 3, so it can lie on it.)"
  (mapv (fn [item-row floor-row]
          (apply str (map #(if (= %1 \.) %2 %1) item-row floor-row)))
        items/stick floor))

(def sign
  "A sign, standing in the grass."
  (mapv (fn [sign-row grass-row]
          (apply str (map #(if (= %1 \.) %2 %1) sign-row grass-row)))
        ["................"
         "................"
         "..222222222222.."
         "..233333333332.."
         "..232232232232.."
         "..233333333332.."
         "..232232322232.."
         "..233333333332.."
         "..222222222222.."
         ".......22......."
         ".......22......."
         ".......22......."
         ".......22......."
         "......2222......"
         "................"
         "................"]
        grass))

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
  importer numbers them from 1."
  (tileset/tileset (into [[:blank  [chr/blank]]
                          [:player (map chr/tile (:tiles art))]
                          [:grass   grass]
                          [:flowers flowers]
                          [:tree    tree]
                          [:water   water]
                          [:path    path]
                          [:brick   brick]
                          [:door    door]
                          [:floor   floor]
                          [:stick   stick-on-floor]
                          [:slot    items/slot]
                          [:cursor  [cursor]]
                          [:font    font/tiles]
                          [:box-font box-font]
                          [:sign    sign]]
                   ;; each item's picture: 2x2 tiles
                         (for [{:keys [id drawing]} items/items]
                           [(keyword "item" (name id)) drawing]))))

(def metatiles
  "The 16x16 blocks that backgrounds are made of: four tiles, and which of
  the four background palettes (`palettes`) colors them. :kind says what
  it's like to walk into: :solid blocks the player, :empty doesn't."
  {:grass   {:tiles (tileset/block tiles :grass)   :palette 0}
   :flowers {:tiles (tileset/block tiles :flowers) :palette 0}
   :tree    {:tiles (tileset/block tiles :tree)    :palette 1 :kind :solid}
   :water   {:tiles (tileset/block tiles :water)   :palette 2 :kind :solid}
   :path    {:tiles (tileset/block tiles :path)    :palette 3}
   :brick   {:tiles (tileset/block tiles :brick)   :palette 3 :kind :solid}
   :door    {:tiles (tileset/block tiles :door)    :palette 3 :kind :door}
   :floor   {:tiles (tileset/block tiles :floor)   :palette 3}
   :stick   {:tiles (tileset/block tiles :stick)   :palette 3 :kind :pickup}
   :sign    {:tiles (tileset/block tiles :sign)    :palette 0 :kind :sign}})

;; ---------------------------------------------------------------------------
;; Colors

(def backgrounds
  "The backdrop colors that A and B step through. Eight, so that
  `color AND 7` is always a valid index."
  (mapv palette/color [:green :light-azure :red :magenta :light-orange :azure :dark-gray :black]))

(def palettes
  "Four background palettes (each three colors besides the backdrop) and
  four for sprites, which are the player's: the importer found them in the
  pictures. Colors are names (see cartridj.palette/colors) or numbers."
  (palette/palettes
   {:backdrop   (first backgrounds)
    :background [[:light-green :dark-green :light-yellow]  ; 0 grass, flowers
                 [:dark-green :green :dark-brown]          ; 1 trees
                 [:azure :light-azure :white]              ; 2 water
                 [:light-orange :brown :dark-brown]]       ; 3 paths and bricks
    :sprites    (:palettes art)}))
