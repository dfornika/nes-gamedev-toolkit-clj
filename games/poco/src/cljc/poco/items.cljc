(ns poco.items
  "The things the player can carry: what they're called, what they look
  like, and what the items screen says about them.

  Each item has a 16x16 drawing (. is see-through, 1 2 3 are the colors
  of its palette), a palette (by name), and two lines of words. Words can be capitals,
  digits, and ! . : - ? and at most 28 letters a line.

  The items are numbered in the order they're listed (there's room for
  eight): that's their place on the items screen, and the bit that says
  you have it. `starting` says which they begin with, and the rest are
  found lying about (see :pickup in poco.world). In the debugger, set the
  variable `items` to give or take them (bit 0 is the first).

  All of it is in resources/assets/items.edn (and the colors in
  palettes.edn): this reads it, and checks it (see cartridj.assets). Add an
  item by adding it there, at the end."
  (:require [cartridj.assets :as assets]
            [poco.util :as util]))

(def ^:private colors
  (assets/palettes (util/inline-edn-resource "assets/palettes.edn") "palettes.edn"))

(def ^:private data (util/inline-edn-resource "assets/items.edn"))

(def slots "How many slots the items screen has." 8)

(def slot
  "The frame drawn in each slot of the items screen (rows of text)."
  (:pixels (assets/drawing (:slot data) colors "items.edn")))

(def ^:private checked (assets/drawings (:items data) colors "items.edn"))

(def palette-slots
  "Which of the items screen's background palettes each palette name is:
  palette 0 is for the words and frames (:ui), and the items' come after
  it, in the order they're first used."
  (assets/slots (map :palette checked) 1 3 "The items"))

(def palettes
  "The four background palettes the items screen is drawn in (three colors
  each, and the backdrop). Palette 0 is the words' and frames', so its
  first color is the color of the letters."
  {:backdrop   (:backdrop data)
   :background (into [(colors :ui)]
                     (map colors (map key (sort-by val palette-slots))))})

(def items
  "[{:id :name :doc :palette (a number: see `palettes`) :drawing (rows of text)} ...]"
  (do
    (when (> (count checked) slots)
      (throw (ex-info (str "The items screen has room for " slots " items, not " (count checked)) {})))
    (vec (for [{:keys [id name doc palette pixels]} checked]
           {:id id :name name :doc doc :palette (palette-slots palette) :drawing pixels}))))

(defn drawing
  "The rows of text of item `id`'s drawing."
  [id]
  (:drawing (first (filter #(= id (:id %)) items))))

(def starting
  "The items the player starts the game with."
  (set (for [{:keys [id starting]} (:items data) :when starting] id)))
