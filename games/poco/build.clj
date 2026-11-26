(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [poco.core :as game]
            [cartridj.rom-edn :refer [container]]
            [cartridj.rom :refer [ines]]))

(def game 'io.github.dfornika/legend-of-poco)
(def version "alpha")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def roms-dir "target/roms")
(def rom-path (format (str roms-dir "/%s-%s.nes") (name game) version))
(def rom-edn-path (format (str roms-dir "/%s-%s.nes.edn") (name game) version))
(def cartridge-edn-path (format (str roms-dir "/%s-%s.cartridge.edn") (name game) version))

(defn clean [_]
  (b/delete {:path roms-dir}))

(defn rom-edn
  [_]
  (let [cartridge (game/build)
        rom-bytes (:rom cartridge)
        container (container rom-bytes)]
    (.mkdirs (io/file roms-dir))
    (with-open [w (io/writer cartridge-edn-path)]
      (pprint/pprint cartridge w))
    (with-open [w (io/writer rom-edn-path)]
      (pprint/pprint container w))))

(defn rom
  [_]
  (let [cartridge (game/build)
        chr (get-in cartridge [:source :chr])
        ines-data (assoc cartridge :chr chr)
        ines-bytes (byte-array (map unchecked-byte (ines ines-data)))]
    (.mkdirs (io/file roms-dir))
    (with-open [out (io/output-stream rom-path)]
      (.write out ines-bytes))))
