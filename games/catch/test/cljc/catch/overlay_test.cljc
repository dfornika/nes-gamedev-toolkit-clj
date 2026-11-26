(ns catch.overlay-test
  "Catch's overlay (the boxes and arrows drawn over the picture), through
  nes-cljs's overlay machinery: needs nes-cljs on the classpath (the :dev
  alias). Moved here from nes-cljs's nes.overlay-test."
  (:require [clojure.test :refer [deftest is testing]]
            [catch.core :as catch]
            [nes.debug-info :as di]
            [nes.overlay :as overlay]))

(defn- ram-with [& addr-values]
  (reduce (fn [ram [a v]] (assoc ram a v)) (vec (repeat 0x800 0)) (partition 2 addr-values)))

(deftest the-games-own-overlay
  (let [built (catch/build)
        sym (:symbols built)
        o (assoc (first (:overlays built)) :fields (di/lens-fields (:memory built)))
        ram (fn [state] (ram-with (:rt/state sym) state
                                  (inc (:player/x sym)) 120 (inc (:player/y sym)) 112
                                  (:star/x sym) 60 (:star/y sym) 70
                                  (inc (:enemy/x sym)) 30 (inc (:enemy/y sym)) 90
                                  (inc (:enemy/vx sym)) 2 (inc (:enemy/vy sym)) 0xFF))   ; +2.0, -1.0
        shapes (overlay/shapes o {:ram (ram 1)})
        labelled (into {} (for [[_ m] shapes :when (:label m)] [(:label m) m]))]
    (is (= [] (overlay/shapes o {:ram (ram 0)})) "nothing on the title screen")
    (is (= #{"player" "star" "spike"} (set (keys labelled))))
    (testing "a touch box is centred on its 16x16 picture"
      (is (= {:x 122.0 :y 115.0 :w 12 :h 12} (select-keys (labelled "player") [:x :y :w :h])))
      (is (= {:x 62 :y 73} (select-keys (labelled "star") [:x :y]))))
    (testing "the arrow points the way the spike is going"
      (let [[_ {:keys [x1 y1 x2 y2]}] (first (filter #(= :line (first %)) shapes))]
        (is (= [38.0 99.0] [x1 y1]))
        (is (> x2 x1) "right")
        (is (< y2 y1) "and up")))))
