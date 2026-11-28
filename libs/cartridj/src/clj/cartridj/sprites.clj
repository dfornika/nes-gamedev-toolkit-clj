(ns cartridj.sprites
  "Turning pictures of sprites (PNG files) into data a game builds from.
  JVM only: it runs when you prepare a game's art, and writes Clojure data
  (EDN) that the game reads when it's built, so building needs no PNG
  reader and works anywhere, including in the browser.

  Each picture is one *frame*: a sprite (or a group of them that move
  together, a metasprite) whose width and height are multiples of 8. For
  each frame this works out what the NES needs:

  - every pixel's NES color. Pixels must be exactly the colors of a NES
    palette table (`:table`: Aseprite's by default, which is what its
    NES palette exports), unless you ask for `:nearest? true`. See-through
    pixels are see-through; black is just a color.
  - the picture cut into 8x8 tiles. A sprite tile has three colors besides
    see-through, because it's drawn with one of a game's four sprite
    palettes, each three colors. A tile that uses more is an error that
    names the tile and its colors, to fix in the picture: nothing is
    repainted behind your back.
  - the sprite palettes: up to four of three colors, found for all the
    frames together (they share the hardware's palette memory), or the ones
    you give with `:palettes`.
  - or, with `:shared`, in two kinds. A picture belongs to a *character*:
    its name without the number (:teenager-01 is the teenager's). The shared
    characters' pictures get palettes that are always loaded (the player).
    Every other character has *guest* palettes, for the tiles that don't fit
    the shared ones: they go in the slots after the shared palettes, and a
    room that shows that character loads them (there are only four slots, so
    the fewer the player takes, the more a guest can have).
  - the tiles' drawings (strings of . 1 2 3, as cartridj.chr reads them),
    with identical ones shared, and for each frame the sprites to draw:
    [tile dx dy palette]. Tiles with nothing in them are left out.

  The result:

    {:palettes [[color color color] ...]   ; NES color numbers
     :tiles    [[\"..1122..\" ...] ...]      ; eight strings each
     :frames   [{:name :players-01
                 :sprites [[tile dx dy palette] ...]} ...]}

  `import!` does it all, and writes the EDN."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cartridj.palette :as palette]
            [cartridj.png :as png]))

(def ^:private tables
  {:aseprite palette/rgb-palette-aseprite
   :jsnes palette/rgb-palette-jsnes})

(def transparent
  "What stands for a see-through pixel while the colors are being worked
  out. (Not a NES color, so it can't be mistaken for one.)"
  -1)

(defn- hex [n] (format "$%02X" n))

(defn- hexes [colors] (str/join " " (map hex (sort colors))))

(defn- nes-color
  "The NES color number for [r g b] in `table`: the exact match, or with
  `nearest?` the closest. Otherwise nil."
  [table nearest? rgb]
  (let [n (palette/nearest rgb table)]
    (when (or nearest? (= rgb (table n)))
      n)))

(defn read-frame
  "The picture in `file` as {:name :colors :problems}: :colors are rows of
  NES color numbers (`transparent` where it's see-through), and :problems
  is a vector of messages about what can't be used. Options: :table
  (:aseprite or :jsnes, default :aseprite), :nearest? (default false)."
  ([file] (read-frame file {}))
  ([file {:keys [table nearest?] :or {table :aseprite}}]
   (let [t (or (tables table) (throw (ex-info (str "Unknown table " table ": :aseprite or :jsnes") {})))
         {:keys [width height pixels]} (png/read-image file)
         name (str/replace (.getName (io/file file)) #"\.png$" "")
         size-problems (when-not (and (pos? width) (pos? height) (zero? (mod width 8)) (zero? (mod height 8)))
                         [(str name ": " width "x" height " pixels; the width and height have to be multiples of 8")])
         bad (atom {})
         colors (vec (for [[y row] (map-indexed vector pixels)]
                       (vec (for [[x [r g b a]] (map-indexed vector row)]
                              (cond
                                (zero? a) transparent
                                :else (or (nes-color t nearest? [r g b])
                                          (do (swap! bad update [r g b] (fnil conj []) [x y])
                                              transparent)))))))]
     {:name (keyword name)
      :colors colors
      :problems (vec (concat size-problems
                             (for [[[r g b] at] @bad]
                               (str name ": rgb(" r " " g " " b ") at " (str/join ", " (map (fn [[x y]] (str "x" x " y" y)) (take 3 at)))
                                    (when (> (count at) 3) (str " and " (- (count at) 3) " more"))
                                    " isn't a color in the " (clojure.core/name table)
                                    " NES palette (pass :nearest? true to use the closest)"))))})))

(defn tile-grid
  "A frame's colors cut into 8x8 tiles, in reading order: a vector of
  {:row :col :colors} (colors as rows)."
  [colors]
  (vec (for [ty (range (quot (count colors) 8))
             tx (range (quot (count (first colors)) 8))]
         {:row ty :col tx
          :colors (mapv #(subvec (nth colors (+ (* ty 8) %)) (* tx 8) (* (inc tx) 8)) (range 8))})))

(defn- tile-colors
  "The colors a tile uses, without the see-through."
  [{:keys [colors]}]
  (disj (set (apply concat colors)) transparent))

(defn- tile-problems
  "A message for each tile of `frame` that uses more than three colors."
  [{:keys [name colors]}]
  (for [t (tile-grid colors)
        :let [used (tile-colors t)]
        :when (> (count used) 3)]
    (str (clojure.core/name name) ": the tile at row " (:row t) ", column " (:col t)
         " (pixels x" (* 8 (:col t)) "-" (+ 7 (* 8 (:col t)))
         " y" (* 8 (:row t)) "-" (+ 7 (* 8 (:row t)))
         ") uses " (count used) " colors, " (hexes used)
         ". A sprite tile has three, besides see-through.")))

(defn- sprite-palettes
  "Up to `max-palettes` palettes of three colors that between them hold
  every tile's colors, or an error message."
  [frames max-palettes]
  (let [found (png/palettes-for (map :colors frames) transparent)]
    (if (> (count found) max-palettes)
      (throw (ex-info (str "These pictures need " (count found) " sprite palettes, and there are "
                           max-palettes ": " (str/join "  " (map #(str "[" (hexes %) "]") found))
                           ". Fewer colors, or colors used together more consistently, would fit.")
                      {:palettes found}))
      found)))

(defn character
  "Whose picture a frame is: :teenager for :teenager-01."
  [frame-name]
  (keyword (str/replace (clojure.core/name frame-name) #"-\d+$" "")))

(defn- guest-palettes
  "{character [palette ...]} for the characters that aren't `shared`: just
  the colors of the tiles that fit none of the `shared-palettes`, in at
  most the slots that are left of `max-palettes`."
  [frames shared-palettes shared max-palettes]
  (let [slots (- max-palettes (count shared-palettes))
        by-character (group-by (comp character :name) (remove #(shared (character (:name %))) frames))]
    (into {}
          (for [[who fs] by-character
                :let [left-over (vec (for [{:keys [colors]} fs
                                           t (tile-grid colors)
                                           :let [used (tile-colors t)]
                                           :when (and (seq used) (not (some #(every? (set %) used) shared-palettes)))]
                                       (:colors t)))]
                :when (seq left-over)]
            (let [found (png/palettes-for left-over transparent)]
              (when (> (count found) slots)
                (throw (ex-info (str (clojure.core/name who) " needs " (count found) " sprite palette(s) of its own, besides the shared "
                                     (count shared-palettes) ", and only " slots " of the " max-palettes " are left: "
                                     (str/join "  " (map #(str "[" (hexes %) "]") found))
                                     ". Fewer colors, or the shared palettes' colors, would fit.")
                                {:character who :palettes found})))
              [who found])))))

(defn- palette-for
  "The index of the first of `palettes` that has all of `used`, or nil."
  [palettes used]
  (first (keep-indexed (fn [i p] (when (every? (set p) used) i)) palettes)))

(defn- drawing
  "A tile's colors as a drawing: . for see-through, 1 2 3 for the
  palette's colors in order."
  [colors palette]
  (let [digit (into {transparent \.} (map vector palette "123"))]
    (mapv #(apply str (map digit %)) colors)))

(defn- flipped
  "The drawing `d` and its mirror images, with the sprite attribute bits
  that draw each back as `d`: [[0 d] ...]. Tried in order, so a drawing
  stays as it is when it can."
  [d]
  (let [across (fn [d] (mapv #(apply str (reverse %)) d))
        down (fn [d] (vec (reverse d)))]
    [[0 d] [0x40 (across d)] [0x80 (down d)] [0xC0 (across (down d))]]))

(defn build
  "Work out the palettes, tiles and frames from `frames` (as from
  `read-frame`), or throw an ex-info that says everything that's wrong
  (its :problems has the messages). Options: :palettes (use these instead
  of finding some: vectors of up to three NES colors), :max-palettes
  (default 4), :first-tile (the number of the first tile in CHR, default
  0), and :shared (a set of characters, as in the namespace's doc: then the
  result also has :guests, {character [palette ...]}, and each frame its
  :character)."
  [frames {:keys [palettes max-palettes first-tile shared] :or {max-palettes 4 first-tile 0}}]
  (let [problems (vec (concat (mapcat :problems frames) (mapcat tile-problems frames)))
        _ (when (seq problems)
            (throw (ex-info (str (count problems) " problem" (when (> (count problems) 1) "s") " with the pictures:\n  "
                                 (str/join "\n  " problems))
                            {:problems problems})))
        grouped? (boolean shared)
        palettes (cond
                   palettes (mapv vec palettes)
                   grouped? (let [mine (filter #(shared (character (:name %))) frames)]
                              (if (seq mine) (sprite-palettes mine max-palettes) []))
                   :else (sprite-palettes frames max-palettes))
        guests (when grouped? (guest-palettes frames palettes shared max-palettes))
        ;; the palettes a frame's tiles can use: the shared ones, then its character's own
        palettes-of (fn [frame-name] (into palettes (get guests (character frame-name))))
        ;; every tile needs a palette that has all its colors
        unplaced (for [{:keys [name colors]} frames
                       t (tile-grid colors)
                       :let [used (tile-colors t)]
                       :when (and (seq used) (not (palette-for (palettes-of name) used)))]
                   (str (clojure.core/name name) ": the tile at row " (:row t) ", column " (:col t)
                        " uses " (hexes used) ", which no palette has together"))
        _ (when (seq unplaced)
            (throw (ex-info (str "The palettes " (pr-str palettes) " don't cover the pictures:\n  "
                                 (str/join "\n  " unplaced))
                            {:problems (vec unplaced)})))
        ;; give each distinct drawing a number, in the order they turn up: a
        ;; drawing that's another one flipped is that one, drawn flipped
        [numbers out]
        (reduce
         (fn [[numbers out] {:keys [name colors]}]
           (let [placed (for [t (tile-grid colors)
                              :let [used (tile-colors t)]
                              :when (seq used)
                              :let [ps (palettes-of name)
                                    p (palette-for ps used)]]
                          [t p (drawing (:colors t) (ps p))])
                 [numbers looks]
                 (reduce (fn [[numbers looks] [_ _ d]]
                           (let [known (first (for [[flips variant] (flipped d)
                                                    :when (contains? numbers variant)]
                                                [(numbers variant) flips]))]
                             (if known
                               [numbers (conj looks known)]
                               (let [n (count numbers)]
                                 [(assoc numbers d n) (conj looks [n 0])]))))
                         [numbers []]
                         placed)]
             [numbers
              (conj out {:name name
                         :character (character name)
                         :sprites (vec (for [[[t p _] [n flips]] (map vector placed looks)]
                                         [(+ first-tile n) (* 8 (:col t)) (* 8 (:row t)) (bit-or p flips)]))})]))
         [{} []]
         frames)]
    (cond-> {:palettes palettes
             :tiles (mapv key (sort-by val numbers))
             :frames (if grouped? out (mapv #(dissoc % :character) out))}
      grouped? (assoc :guests guests))))

(defn- pictures
  "The PNG files in `dir` (or each of the directories `dir` is a collection
  of, in turn), sorted by name in each; or just the ones named in `only`
  (names, with or without .png)."
  [dir only]
  (let [dirs (if (coll? dir) dir [dir])
        files (->> dirs
                   (mapcat (fn [dir]
                             (->> (.listFiles (io/file dir))
                                  (filter #(str/ends-with? (.getName %) ".png"))
                                  (sort-by #(.getName %))))))
        wanted (when only (set (map #(str/replace (name %) #"\.png$" "") only)))]
    (if wanted
      (let [found (filter #(wanted (str/replace (.getName %) #"\.png$" "")) files)]
        (when (not= (count found) (count wanted))
          (throw (ex-info (str "Not all of " (pr-str only) " are in " (pr-str dir)) {})))
        found)
      files)))

(defn write-edn
  "`art` (as from `build`) as readable EDN text: a drawing per line."
  [{:keys [palettes guests tiles frames]}]
  (str "{:palettes " (pr-str palettes) "\n"
       (when guests (str " :guests " (pr-str guests) "\n"))
       " :tiles\n [" (str/join "\n  " (map pr-str tiles)) "]\n"
       " :frames\n [" (str/join "\n  " (map pr-str frames)) "]}\n"))

(defn import!
  "Read the pictures in `dir` (a directory, or a collection of them: the
  characters share the sprite palettes and tiles), and write the art for a
  game to `out`.
  Options: :only (just these pictures, by name), and the options of
  `read-frame` and `build`. Returns what it wrote. Throws, with everything
  that's wrong with the pictures, if they can't be used."
  [dir out & {:keys [only] :as opts}]
  (let [frames (mapv #(read-frame % opts) (pictures dir only))
        art (build frames opts)]
    (spit out (write-edn art))
    art))
