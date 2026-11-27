(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [poco.import :as import]
            [cartridj.rom-edn :refer [container]]
            [cartridj.rom :refer [ines]]))

(def game 'io.github.dfornika/legend-of-poco)
(def version "alpha")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def roms-dir "target/roms")
(def rom-path (format (str roms-dir "/%s-%s.nes") (name game) version))
(def rom-edn-path (format (str roms-dir "/%s-%s.nes.edn") (name game) version))
(def cartridge-edn-path (format (str roms-dir "/%s-%s.cartridge.edn") (name game) version))

(defn- build-game
  "The game's cartridge. (The game is loaded now, not when this file is: the
  import tasks have to run when the game can't be loaded, because its art
  is what they make.)"
  []
  ((requiring-resolve 'poco.core/build)))

(defn clean [_]
  (b/delete {:path roms-dir}))

(defn rom-edn
  [_]
  (let [cartridge (build-game)
        rom-bytes (:rom cartridge)
        container (container rom-bytes)]
    (.mkdirs (io/file roms-dir))
    (with-open [w (io/writer cartridge-edn-path)]
      (pprint/pprint cartridge w))
    (with-open [w (io/writer rom-edn-path)]
      (pprint/pprint container w))))

(defn rom
  [_]
  (let [cartridge (build-game)
        chr (get-in cartridge [:source :chr])
        ines-data (assoc cartridge :chr chr)
        ines-bytes (byte-array (map unchecked-byte (ines ines-data)))]
    (.mkdirs (io/file roms-dir))
    (with-open [out (io/output-stream rom-path)]
      (.write out ines-bytes))))

(defn import
  "Turn the pictures in resources/assets/sprites into players.edn (see
  poco.import). `:only [\"players-03\"]` imports just those."
  [{:keys [only]}]
  (import/import-sprites only))

(defn tiles
  "Turn the cells of the tilesheets (resources/assets/tiles/*.png) that
  tiles.edn uses into resources/assets/sheets.edn (see poco.import).
  `:nearest? true` takes the closest of a palette's colors for pixels
  that aren't exactly one."
  [opts]
  (import/import-tiles (select-keys opts [:nearest?])))
