(ns cartridj.sheets-test
  "The sheet importer, on small pictures made here. JVM only: it reads PNGs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cartridj.assets :as assets]
            [cartridj.palette :as palette]
            [cartridj.sheets :as sheets])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "sheets-test" (make-array FileAttribute 0))))

(defn- argb [[r g b] & [a]]
  (unchecked-int (bit-or (bit-shift-left (or a 255) 24) (bit-shift-left r 16) (bit-shift-left g 8) b)))

(defn- picture!
  "A PNG: `rows` of characters, each one a color of `colors` ({char [r g b]}),
  or see-through if it isn't there."
  [file colors rows]
  (let [image (BufferedImage. (count (first rows)) (count rows) BufferedImage/TYPE_INT_ARGB)]
    (doseq [[y row] (map-indexed vector rows), [x c] (map-indexed vector row)]
      (.setRGB image x y (if-let [rgb (colors c)] (argb rgb) 0)))
    (ImageIO/write image "png" file)))

(def table palette/rgb-palette-aseprite)
(def palettes {:wood [0x27 0x17 0x07]})
(def wood (mapv table [0x27 0x17 0x07]))
(def colors {\a (wood 0) \b (wood 1) \c (wood 2)})

;; two 8x8 cells side by side
(def sheet-rows
  ["aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."
   "aabb....cccc...."])

(defn- import-from [rows asset & [options]]
  (let [dir (temp-dir)]
    (picture! (io/file dir "s.png") colors rows)
    (sheets/import-cells (merge {:dir dir :sheets {:s {:png "s.png" :cell [8 8]}}
                                 :assets [asset] :palettes palettes}
                                options))))

(defn- error-of [f] (try (f) nil (catch Exception e (ex-message e))))

(defn- asset [cell] {:id :thing :palette :wood :source {:sheet :s :cell cell}})

(deftest cells-become-drawings
  (let [cells (import-from sheet-rows (asset [0 0]))]
    (is (= (vec (repeat 8 "1122....")) (get-in cells [:s [0 0]])))
    (is (= (vec (repeat 8 "3333....")) (get-in (import-from sheet-rows (asset [1 0])) [:s [1 0]])))))

(deftest what-cant-be-used-is-reported
  (testing "a color that isn't the palette's"
    (let [dir (temp-dir)]
      (picture! (io/file dir "s.png") (assoc colors \x [1 2 3]) ["aabb...x" "aabb...." "aabb...." "aabb...." "aabb...." "aabb...." "aabb...." "aabb...."])
      (let [e (error-of #(sheets/import-cells {:dir dir :sheets {:s {:png "s.png" :cell [8 8]}} :assets [(asset [0 0])] :palettes palettes}))]
        (is (re-find #"#010203 at x7 y0" e))
        (is (re-find #":thing \(cell \[0 0\] of :s\)" e)))
      (testing "unless the closest will do"
        (is (= "1122...3" (first (get-in (sheets/import-cells {:dir dir :sheets {:s {:png "s.png" :cell [8 8]}} :assets [(asset [0 0])]
                                                               :palettes palettes :nearest? true})
                                         [:s [0 0]])))))))
  (testing "a cell that isn't on the sheet"
    (is (re-find #"isn't on the sheet" (error-of #(import-from sheet-rows (asset [2 0]))))))
  (testing "a sheet that isn't there"
    (is (re-find #"no sheet called :nope" (error-of #(import-from sheet-rows (assoc-in (asset [0 0]) [:source :sheet] :nope))))))
  (testing "a palette that isn't there"
    (is (re-find #"no palette :metal" (error-of #(import-from sheet-rows (assoc (asset [0 0]) :palette :metal)))))))

(deftest a-sheet-and-back
  (let [dir (temp-dir)
        drawings [{:id :a :palette :wood :pixels (vec (repeat 8 "1122...."))}
                  {:id :b :palette :wood :pixels (vec (repeat 8 "..3333.."))}
                  {:id :c :palette :wood :pixels (vec (repeat 8 "3......1"))}]
        where (sheets/write-sheet! (io/file dir "s.png") [8 8] 2 drawings palettes)
        cells (sheets/import-cells {:dir dir :sheets {:s {:png "s.png" :cell [8 8]}} :palettes palettes
                                    :assets (for [{:keys [id palette]} drawings]
                                              {:id id :palette palette :source {:sheet :s :cell (where id)}})})]
    (is (= {:a [0 0] :b [1 0] :c [0 1]} where))
    (is (= (for [d drawings] (:pixels d)) (for [d drawings] (get-in cells [:s (where (:id d))]))))
    (testing "the EDN written reads back the same, and fills in drawings"
      (let [out (io/file dir "sheets.edn")]
        (sheets/write-cells! out cells ";; generated\n")
        (is (= cells (edn/read-string (slurp out))))
        (is (= (map :pixels drawings)
               (map :pixels (assets/with-sources (for [d drawings] (-> d (dissoc :pixels) (assoc :source {:sheet :s :cell (where (:id d))})))
                              (edn/read-string (slurp out)) "x"))))))))
