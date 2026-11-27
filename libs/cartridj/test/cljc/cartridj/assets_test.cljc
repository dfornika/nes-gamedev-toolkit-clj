(ns cartridj.assets-test
  (:require [clojure.test :refer [deftest is testing]]
            [cartridj.assets :as assets]))

(defn- error-of [f]
  (try (f) nil (catch #?(:clj Exception :cljs :default) e (ex-message e))))

(def named {:wood [:light-orange :brown :dark-brown]})
(def row "........")
(def stick {:id :stick :palette :wood :pixels (vec (repeat 8 row)) :name "STICK"})

(deftest a-good-drawing
  (is (= (assoc stick :colors [:light-orange :brown :dark-brown] :width 8 :height 8)
         (assets/drawing stick named "items.edn")))
  (is (= {:a [1 2 3]} (assets/palettes {:a [1 2 3]} "p.edn"))))

(deftest mistakes-say-where
  (testing "a palette that isn't there"
    (let [e (error-of #(assets/drawing (assoc stick :palette :metal) named "items.edn"))]
      (is (re-find #"items.edn \(:stick\)" e))
      (is (re-find #":metal" e))))
  (testing "a size that isn't tiles"
    (is (re-find #"multiple of 8" (error-of #(assets/drawing (assoc stick :pixels (repeat 7 row)) named "x")))))
  (testing "rows of different widths"
    (is (re-find #"pixels wide" (error-of #(assets/drawing (assoc stick :pixels (cons "......." (repeat 7 row))) named "x")))))
  (testing "a pixel that isn't one"
    (is (re-find #"\\4" (error-of #(assets/drawing (assoc stick :pixels (cons "...4...." (repeat 7 row))) named "x")))))
  (testing "a palette with the wrong number of colors"
    (is (re-find #"three colors" (error-of #(assets/palettes {:a [:red :white]} "p.edn")))))
  (testing "a color that doesn't exist"
    (is (re-find #"Unknown color" (error-of #(assets/palettes {:a [:red :white :nope]} "p.edn")))))
  (testing "the same id twice"
    (is (re-find #":stick" (error-of #(assets/drawings [stick stick] named "x"))))))

(deftest drawings-over-others
  (let [floor {:id :floor :palette :wood :pixels (vec (repeat 8 "11111111"))}
        mark  {:id :mark :over :floor :palette :wood :pixels (into ["..2....."] (repeat 7 row))}
        [_ composed] (assets/drawings [floor mark] named "x")]
    (is (= (into ["11211111"] (repeat 7 "11111111")) (:pixels composed)))
    (testing "the one underneath can come later"
      (is (= (:pixels composed) (:pixels (first (assets/drawings [mark floor] named "x"))))))
    (testing "mistakes"
      (is (re-find #"isn't there" (error-of #(assets/drawings [mark] named "x"))))
      (is (re-find #"another palette"
                   (error-of #(assets/drawings [floor (assoc mark :palette :wood2)] (assoc named :wood2 [1 2 3]) "x"))))
      (is (re-find #"not the size"
                   (error-of #(assets/drawings [floor (assoc mark :pixels (repeat 16 (apply str (repeat 16 \.))))] named "x")))))))

(deftest drawings-from-sheets
  (let [sheets {:s {[0 0] (vec (repeat 8 row))}}
        from (fn [cell] [{:id :x :palette :wood :source {:sheet :s :cell cell}}])]
    (is (= (vec (repeat 8 row)) (:pixels (first (assets/with-sources (from [0 0]) sheets "tiles.edn")))))
    (is (re-find #"hasn't been imported" (error-of #(assets/with-sources (from [1 0]) sheets "tiles.edn"))))
    (is (= [stick] (assets/with-sources [stick] sheets "x")) "drawings with their own pixels are left alone")))

(deftest palettes-get-slots
  (is (= {:wood 1 :pizza 2} (assets/slots [:wood :wood :pizza :wood] 1 3 "items")))
  (is (re-find #"4 palettes" (error-of #(assets/slots [:a :b :c :d] 1 3 "items")))))
