(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [ladder.core :as ladder]
            [cartridj.debug-info :as debug-info]))

(def roms-dir "target/roms")

(defn clean [_]
  (b/delete {:path roms-dir}))

(defn rom
  "Every rung of the ladder as a .nes file (ladder-nil.nes, ...), each
  with its names beside it (ladder-nil.debug.edn: see cartridj.debug-info)."
  [_]
  (.mkdirs (io/file roms-dir))
  (doseq [{:keys [id]} ladder/rungs
          :let [built (ladder/build id)
                base (str roms-dir "/ladder-" (name id))]]
    (with-open [out (io/output-stream (str base ".nes"))]
      (.write out (byte-array (map unchecked-byte (:rom built)))))
    (spit (str base ".debug.edn") (debug-info/edn built))))
