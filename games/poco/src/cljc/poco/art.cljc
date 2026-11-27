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
            [poco.util :as util]))

;; ---------------------------------------------------------------------------
;; The player

(def art
  "What the importer made from the PNGs: {:palettes :tiles :frames}.
  GENERATED: change the pictures, not this."
  (util/inline-edn-resource "assets/sprites/players.edn"))

(def poses
  "Which picture is which. Each direction the player can face has a
  :stand picture and a :walk cycle of four, named after the PNGs
  (players-03 is the file players-03.png). A picture that isn't imported
  yet (see `missing`) is drawn as the first one that is, so you can fill
  these in as you draw them.

  :flip? draws the picture mirrored. Left is right backwards, so once
  there are side-on pictures of the player facing right, only :right needs
  them. (Until then both use the front ones.)"
  {:down  {:stand :players-03 :walk [:players-01 :players-03 :players-02 :players-03]}
   :up    {:stand :players-06 :walk [:players-04 :players-06 :players-05 :players-06]}
   :right {:stand :players-03 :walk [:players-01 :players-03 :players-02 :players-03]}
   :left  {:stand :players-03 :walk [:players-01 :players-03 :players-02 :players-03] :flip? true}})

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
  (sort (remove frames (distinct (mapcat (fn [{:keys [stand walk]}] (cons stand walk)) (vals poses))))))

(defn pieces
  "What to draw for `picture` (a name from `poses`), mirrored if `flip?`."
  [picture flip?]
  (let [found (or (frames picture)
                  (:sprites (first (:frames art)))
                  (throw (ex-info "No player pictures at all: run clojure -T:build import" {})))]
    (if flip?
      (tileset/flip-pieces found (width found))
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

(def tiles
  "Everything in the cartridge's graphics, with names (see
  cartridj.tileset). Tile 0 is blank, and the player's come next: the
  importer numbers them from 1."
  (tileset/tileset [[:blank  [chr/blank]]
                    [:player (map chr/tile (:tiles art))]
                    [:grass   grass]
                    [:flowers flowers]
                    [:tree    tree]
                    [:water   water]
                    [:path    path]
                    [:brick   brick]
                    [:font    font/tiles]]))

(def metatiles
  "The 16x16 blocks that backgrounds are made of: four tiles, and which of
  the four background palettes (`palettes`) colors them. :kind says what
  it's like to walk into: :solid blocks the player, :empty doesn't."
  {:grass   {:tiles (tileset/block tiles :grass)   :palette 0}
   :flowers {:tiles (tileset/block tiles :flowers) :palette 0}
   :tree    {:tiles (tileset/block tiles :tree)    :palette 1 :kind :solid}
   :water   {:tiles (tileset/block tiles :water)   :palette 2 :kind :solid}
   :path    {:tiles (tileset/block tiles :path)    :palette 3}
   :brick   {:tiles (tileset/block tiles :brick)   :palette 3 :kind :solid}})

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
