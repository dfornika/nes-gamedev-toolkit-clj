(ns cartridj.assets
  "Art as data: drawings and palettes in EDN files, checked as they're read.

  A *palette* has a name and three colors (names from cartridj.palette, or
  numbers), the colors that the digits 1 2 3 of a drawing stand for:

    {:wood [:light-orange :brown :dark-brown]
     :ui   [:white :light-gray :gray]}

  A *drawing* is a map: its :id, the name of its :palette, and its :pixels,
  rows of text, one character a pixel (. is see-through). Both sides are a
  multiple of 8, so it's a whole number of tiles. Anything else in the map
  (a name, some words, ...) is the game's, and comes through untouched.

  A drawing can say it's `:over` another's (by :id): the other drawing shows
  through wherever it's see-through. That's for a background tile with
  something on it (a stick on the floor, a sign in the grass): a background
  tile has no see-through of its own, the NES shows the backdrop color there.
  They have to be the same size and the same palette.

    {:id :stick
     :palette :wood
     :pixels [\"................\"
              \".............123\"
              ...]}

  A drawing's pixels can instead come from a sheet, a PNG drawn in a pixel
  editor: `:source {:sheet :outdoors :cell [0 0]}` (see cartridj.sheets, which
  reads the PNG, and `with-sources`, which fills the pixels in).

  Nothing here reads files: games read their EDN (in ClojureScript, when
  the game is compiled: see poco.util/inline-edn-resource), and hand the
  data over. Every function takes `where`, a few words that say what's being
  read (\"items.edn\"), so that a mistake in the data says where it is."
  (:require [clojure.string :as str]
            [cartridj.palette :as palette]))

(defn- fail [where message data]
  (throw (ex-info (str where ": " message) (assoc data :where where))))

(defn palettes
  "Check `named` ({name [c1 c2 c3]}): every palette has three colors that
  exist. Returns it, with the colors as names and numbers as they were."
  [named where]
  (doseq [[name colors] named]
    (when-not (and (sequential? colors) (= 3 (count colors)))
      (fail where (str "the palette " (pr-str name) " needs three colors, and has " (pr-str colors))
            {:palette name}))
    (doseq [c colors]
      (try (palette/color c)
           (catch #?(:clj Exception :cljs :default) e
             (fail where (str "the palette " (pr-str name) ": " (ex-message e)) {:palette name})))))
  named)

(defn with-sources
  "Drawings that come from a sheet (a :source {:sheet name :cell [col row]},
  see cartridj.sheets) given their :pixels, from `sheets`, what
  cartridj.sheets imported: {sheet {[col row] rows}}. The others are as they
  were."
  [assets sheets where]
  (mapv (fn [{:keys [id source] :as asset}]
          (if-not source
            asset
            (let [{:keys [sheet cell]} source
                  rows (get-in sheets [sheet cell])]
              (when-not rows
                (fail (str where " (" (pr-str id) ")")
                      (str "its cell " (pr-str cell) " of the sheet " (pr-str sheet)
                           " hasn't been imported (see the game's import task)")
                      {}))
              (assoc asset :pixels rows))))
        assets))

(defn drawing
  "Check one drawing against `named` palettes. Returns it with :colors (the
  palette's three), :width and :height (in pixels) added."
  [{:keys [id palette pixels] :as asset} named where]
  (let [where (str where " (" (pr-str id) ")")]
    (when-not (keyword? id)
      (fail where "every drawing needs an :id, a keyword" {}))
    (when-not (contains? named palette)
      (fail where (str "its palette " (pr-str palette) " isn't one of " (pr-str (sort (keys named))))
            {:palette palette}))
    (when-not (and (sequential? pixels) (seq pixels) (every? string? pixels))
      (fail where "its :pixels are rows of text" {}))
    (let [w (count (first pixels)) h (count pixels)]
      (when-not (every? #(= w (count %)) pixels)
        (fail where (str "its rows are not all " w " pixels wide") {}))
      (when-not (and (pos? w) (zero? (mod w 8)) (zero? (mod h 8)))
        (fail where (str "it is " w "x" h " pixels: both need to be a multiple of 8") {}))
      (when-let [c (first (remove #{\. \1 \2 \3} (apply concat pixels)))]
        (fail where (str "pixels are . 1 2 or 3, not " (pr-str c)) {}))
      (assoc asset :colors (vec (named palette)) :width w :height h))))

(defn- over
  "`top` drawn over `under`: where a pixel of `top` is see-through, the
  pixel of `under`."
  [top under where]
  (let [where (str where " (" (pr-str (:id top)) ")")]
    (when-not (:pixels under)
      (fail where (str "it's :over " (pr-str (:over top)) ", which isn't there") {}))
    (when (:over under)
      (fail where (str "it's :over " (pr-str (:id under)) ", which is itself :over another") {}))
    (when-not (and (= (:width top) (:width under)) (= (:height top) (:height under)))
      (fail where (str "it's not the size of " (pr-str (:id under))) {}))
    (when-not (= (:palette top) (:palette under))
      (fail where (str "it's :over " (pr-str (:id under)) " in another palette: " (pr-str (:palette top))
                       ", and " (pr-str (:palette under)) ". A background tile can only have one") {}))
    (assoc top :pixels (mapv (fn [a b] (apply str (map #(if (= %1 \.) %2 %1) a b)))
                             (:pixels top) (:pixels under)))))

(defn drawings
  "Check a vector of drawings (see `drawing`), and that no two share an :id.
  Drawings that are :over others are drawn on them."
  [assets named where]
  (let [checked (mapv #(drawing % named where) assets)
        twice (->> (map :id checked) frequencies (filter #(> (val %) 1)) (map key) sort)
        by-id (into {} (map (juxt :id identity)) checked)]
    (when (seq twice)
      (fail where (str "more than one drawing is called " (str/join ", " (map pr-str twice))) {}))
    (mapv (fn [d] (if (:over d) (over d (by-id (:over d)) where) d)) checked)))

(defn slots
  "Give each palette in `names` (a name, or several: the same one only gets
  a slot once) its own number, from `first-slot`, in the order they first
  appear: {name number}. At most `limit` of them: a screen has only four
  background palettes. `what` says what they're for, for the error."
  [names first-slot limit what]
  (let [distinct-names (vec (distinct names))]
    (when (> (count distinct-names) limit)
      (throw (ex-info (str what " need " (count distinct-names) " palettes, and there's room for "
                           limit ": " (pr-str distinct-names))
                      {:palettes distinct-names})))
    (into {} (map-indexed (fn [i name] [name (+ first-slot i)]) distinct-names))))
