(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [alienscape.core :as game]
            [cartridj.rom-edn :refer [container]]
            [cartridj.debug-info :as debug-info]))

(def game 'io.github.dfornika/alienscape)
(def version "alpha")
(def roms-dir "target/roms")
(def rom-path (format (str roms-dir "/%s-%s.nes") (name game) version))
(def debug-path (format (str roms-dir "/%s-%s.debug.edn") (name game) version))
(def rom-edn-path (format (str roms-dir "/%s-%s.nes.edn") (name game) version))

(defn clean [_]
  (b/delete {:path roms-dir}))

(defn rom
  "The .nes file, and beside it the names an emulator's inspectors can
  show (labels, variables: see cartridj.debug-info)."
  [_]
  (let [built (game/build)]
    (.mkdirs (io/file roms-dir))
    (with-open [out (io/output-stream rom-path)]
      (.write out (byte-array (map unchecked-byte (:rom built)))))
    (spit debug-path (debug-info/edn built))))

(defn rom-edn
  [_]
  (let [built (game/build)]
    (.mkdirs (io/file roms-dir))
    (with-open [w (io/writer rom-edn-path)]
      (pprint/pprint (container (:rom built)) w))))
