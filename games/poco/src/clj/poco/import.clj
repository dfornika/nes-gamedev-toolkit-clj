(ns poco.import
  "Turns the pictures in resources/assets/sprites (drawn in Aseprite, one
  PNG per frame, 16x32) into the data poco.core builds from:
  resources/assets/sprites/players.edn, which says which colors are in the
  sprite palettes, what the tiles look like, and where each frame's eight
  sprites go. See cartridj.sprites for what it checks.

    clojure -T:build import                          ; every picture
    clojure -T:build import :only '[\"players-03\"]'   ; just some

  Run it again when the pictures change. The EDN it writes is kept in the
  repository, so building the game needs no PNG reader."
  (:require [cartridj.sprites :as sprites]))

(def pictures "resources/assets/sprites")
(def output "resources/assets/sprites/players.edn")

(defn import-sprites
  "`only` is a vector of picture names (\"players-03\"), or nil for all."
  [only]
  (let [art (sprites/import! pictures output
                             :only only
                             ;; tile 0 is the blank one (poco.core/tiles), so ours start at 1
                             :first-tile 1)]
    (println (count (:frames art)) "frames:" (mapv :name (:frames art)))
    (println (count (:tiles art)) "tiles;" (count (:palettes art)) "sprite palettes:"
             (mapv #(mapv (partial format "$%02X") %) (:palettes art)))
    (println "wrote" output)
    art))
