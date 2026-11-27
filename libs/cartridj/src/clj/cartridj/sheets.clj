(ns cartridj.sheets
  "Drawings from sheets: PNG files with a grid of cells, each a drawing
  (a background tile, an item...), so they can be drawn in a pixel editor
  instead of typed as text. JVM only: it runs when you prepare a game's art
  and writes EDN, which the game reads (see cartridj.assets/with-sources).

  A drawing says where it is on a sheet:

    {:id :grass :palette :grass :source {:sheet :outdoors :cell [0 0]}}

  and the game says where the sheets are, and how big their cells are:

    :sheets {:outdoors {:png \"tiles/outdoors.png\" :cell [16 16]}}

  `import-cells` reads the cells the drawings use, and turns each into rows of
  text (. 1 2 3: see cartridj.assets) using the drawing's palette:

  - a see-through pixel (alpha 0) is `.`: for a background tile, the backdrop
  - every other pixel has to be exactly one of the palette's three colors
    (in the NES palette `:table`: Aseprite's by default, which LibreSprite
    has too), unless you ask for `:nearest? true`, which picks the closest
    of the three. Nothing is repainted behind your back.

  `write-sheet!` goes the other way: it draws drawings onto a sheet, to start
  from the art you have."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cartridj.palette :as palette]
            [cartridj.png :as png])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(def ^:private tables
  {:aseprite palette/rgb-palette-aseprite
   :jsnes palette/rgb-palette-jsnes})

(defn- table-of [table]
  (or (tables table) (throw (ex-info (str "Unknown table " table ": :aseprite or :jsnes") {}))))

(defn- hex [[r g b]] (format "#%02x%02x%02x" r g b))

(defn- palette-rgb
  "The [r g b] of each of a palette's three colors (names or numbers)."
  [colors table]
  (mapv #(table (palette/color %)) colors))

(defn- distance [[r g b] [r2 g2 b2]]
  (+ (* (- r r2) (- r r2)) (* (- g g2) (- g g2)) (* (- b b2) (- b b2))))

(defn- cell-rows
  "One cell of an image as {:rows :problems}. `rgbs` are the palette's colors."
  [{:keys [width height pixels]} [cw ch] [col row] rgbs nearest?]
  (let [x0 (* col cw) y0 (* row ch)]
    (if (or (neg? col) (neg? row) (> (+ x0 cw) width) (> (+ y0 ch) height))
      {:problems [(str "the cell " [col row] " isn't on the sheet, which is " width "x" height " pixels: "
                       (quot width cw) " cells across and " (quot height ch) " down")]}
      (let [bad (atom {})
            rows (vec (for [y (range ch)]
                        (apply str
                               (for [x (range cw)
                                     :let [[r g b a] (get-in pixels [(+ y0 y) (+ x0 x)])
                                           rgb [r g b]
                                           i (.indexOf ^java.util.List rgbs rgb)]]
                                 (cond
                                   (zero? a) \.
                                   (not= 255 a) (do (swap! bad update :half (fnil conj []) [x y]) \.)
                                   (>= i 0) (nth "123" i)
                                   nearest? (nth "123" (apply min-key #(distance rgb (nth rgbs %)) (range 3)))
                                   :else (do (swap! bad update rgb (fnil conj []) [x y]) \.))))))]
        {:rows rows
         :problems (vec (for [[k at] @bad
                              :let [where (str (str/join ", " (map (fn [[x y]] (str "x" x " y" y)) (take 3 at)))
                                               (when (> (count at) 3) (str " and " (- (count at) 3) " more")))]]
                          (if (= k :half)
                            (str "partly see-through pixels at " where " (a pixel is see-through or not)")
                            (str (hex k) " at " where " isn't one of the palette's colors: "
                                 (str/join " " (map hex rgbs))
                                 " (pass :nearest? true to use the closest)"))))}))))

(defn import-cells
  "Read the cells that `assets` use: drawings with a :palette (a name in
  `palettes`) and a :source {:sheet name :cell [col row]}. `sheets` is
  {name {:png path :cell [w h]}}, the paths relative to `dir`. Returns
  {sheet {[col row] rows}} (rows of text), or throws with everything that's
  wrong. Options: :table (:aseprite or :jsnes), :nearest?."
  [{:keys [dir sheets assets palettes table nearest?] :or {table :aseprite}}]
  (let [t (table-of table)
        images (memoize (fn [sheet]
                          (let [{:keys [png]} (get sheets sheet)]
                            (when-not png
                              (throw (ex-info (str "There's no sheet called " (pr-str sheet) ". There are " (pr-str (sort (keys sheets)))) {})))
                            (let [f (io/file dir png)]
                              (when-not (.exists f)
                                (throw (ex-info (str "The sheet " (pr-str sheet) "'s picture isn't there: " f) {})))
                              (png/read-image f)))))
        results (for [{:keys [id palette source] :as asset} assets
                      :when source
                      :let [{:keys [sheet cell]} source
                            colors (or (palettes palette)
                                       (throw (ex-info (str (pr-str id) ": there's no palette " (pr-str palette)) {})))
                            cell-size (get-in sheets [sheet :cell] [16 16])
                            result (cell-rows (images sheet) cell-size cell (palette-rgb colors t) nearest?)]]
                  (assoc result :id id :sheet sheet :cell cell))
        problems (for [{:keys [id sheet cell problems]} results, p problems]
                   (str (pr-str id) " (cell " cell " of " (pr-str sheet) "): " p))]
    (when (seq problems)
      (throw (ex-info (str "These pictures can't be used:\n  " (str/join "\n  " problems)) {:problems (vec problems)})))
    (reduce (fn [acc {:keys [sheet cell rows]}] (assoc-in acc [sheet cell] rows)) {} results)))

(defn write-cells!
  "Write what `import-cells` found to `out`, as EDN that reads back the
  same, and is easy to look at: a cell to a few lines."
  [out cells header]
  (spit out
        (str header
             "{"
             (str/join "\n\n "
                       (for [[sheet by-cell] (sort-by (comp str key) cells)]
                         (str (pr-str sheet) "\n {"
                              (str/join "\n  "
                                        (for [[cell rows] (sort-by (fn [[[c r] _]] [r c]) by-cell)]
                                          (str (pr-str cell) " [" (str/join (str "\n" (apply str (repeat (+ 4 (count (pr-str cell))) \space)))
                                                                            (map pr-str rows)) "]")))
                              "}")))
             "}\n")))

(defn write-sheet!
  "Draw `drawings` ({:id :palette :pixels}, as in cartridj.assets) onto a
  new sheet at `file`, `columns` cells across, in order, in the colors of
  their palettes. Returns {id [col row]}."
  [file [cw ch] columns drawings palettes & {:keys [table] :or {table :aseprite}}]
  (let [t (table-of table)
        rows (quot (+ (count drawings) columns -1) columns)
        image (BufferedImage. (* columns cw) (* rows ch) BufferedImage/TYPE_INT_ARGB)]
    (into {}
          (for [[i {:keys [id palette pixels]}] (map-indexed vector drawings)
                :let [col (mod i columns) row (quot i columns)
                      rgbs (palette-rgb (palettes palette) t)]]
            (do
              (when-not (and (= ch (count pixels)) (every? #(= cw (count %)) pixels))
                (throw (ex-info (str (pr-str id) " isn't " cw "x" ch " pixels") {})))
              (doseq [[y line] (map-indexed vector pixels)
                      [x c] (map-indexed vector line)]
                (.setRGB image (+ (* col cw) x) (+ (* row ch) y)
                         (if (= c \.)
                           0
                           (let [[r g b] (nth rgbs (dec (Character/digit ^char c 10)))]
                             (unchecked-int (bit-or 0xFF000000 (bit-shift-left r 16) (bit-shift-left g 8) b))))))
              [id [col row]])))
    (io/make-parents file)
    (ImageIO/write image "png" (io/file file))
    (into {} (for [[i {:keys [id]}] (map-indexed vector drawings)] [id [(mod i columns) (quot i columns)]]))))
