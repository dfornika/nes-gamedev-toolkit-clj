(ns zeropage.nestest-test
  "Runs a CPU (zeropage.cpu.simple, or `harness/*cpu*`) through nestest,
  kevtris's CPU test ROM, comparing its registers and cycle count before
  every instruction with the reference log from a known-good emulator
  (Nintendulator).

  The simple CPU runs only official instructions, so the comparison stops
  where nestest's tests of the unofficial ones begin (line 5,004 of the
  log). A CPU that runs them all can be checked on every line: nes-cljs
  does that for its own.

  nestest is in test-roms/nestest (see the README there). NESTEST_DIR can
  point somewhere else."
  (:require [cljs.test :refer [deftest is]]
            [zeropage.cpu :as cpu]
            [zeropage.harness :as harness]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- find-dir []
  (some #(when (and % (.existsSync fs (.join path % "nestest.nes"))
                    (.existsSync fs (.join path % "nestest.log")))
           %)
        [(.. js/process -env -NESTEST_DIR)
         "test-roms/nestest"]))

(defn- parse-line
  "One line of nestest.log: the state before the instruction."
  [line]
  (let [field (fn [k] (js/parseInt (second (re-find (re-pattern (str k ":([0-9A-F]+)")) line)) 16))]
    {:pc (js/parseInt (subs line 0 4) 16)
     :a (field "A") :x (field "X") :y (field "Y") :p (field "P") :sp (field "SP")
     :cycles (js/parseInt (second (re-find #"CYC:(\d+)" line)))
     :unofficial? (= "*" (subs line 15 16))
     :line line}))

(deftest nestest-official-instructions
  (if-let [dir (find-dir)]
    (let [rom (.readFileSync fs (.join path dir "nestest.nes"))
          expected (->> (.split (str (.readFileSync fs (.join path dir "nestest.log"))) "\n")
                        (remove #(< (count %) 20))
                        (map parse-line)
                        (take-while (complement :unofficial?))
                        vec)
          mem (js/Uint8Array. 0x10000)
          c (harness/*cpu* {:read #(aget mem %) :write (fn [a v] (when (< a 0x8000) (aset mem a v)))})]
      ;; NROM-128: the 16 KB program appears at both $8000 and $C000
      (.set mem (.subarray rom 16 (+ 16 16384)) 0x8000)
      (.set mem (.subarray rom 16 (+ 16 16384)) 0xC000)
      ;; nestest's automatic mode starts at $C000, after RESET's 7 cycles
      (cpu/set-registers! c {:pc 0xC000})
      (let [mismatch (loop [[e & more] expected n 0 cycles 7]
                       (if-not e
                         nil
                         (let [actual (assoc (select-keys (cpu/registers c) [:pc :a :x :y :p :sp]) :cycles cycles)]
                           (if (= actual (dissoc e :unofficial? :line))
                             (let [taken (cpu/step! c)]
                               (if taken
                                 (recur more (inc n) (+ cycles taken))
                                 {:line (inc n) :expected (:line e) :actual :not-an-instruction}))
                             {:line (inc n) :expected (:line e) :actual actual}))))]
        (is (nil? mismatch) (str "differs from nestest.log: " (pr-str mismatch)))
        ;; nestest's own verdict on the official instructions: 0 at $02
        (is (= 0 (aget mem 2)) "nestest reports no errors")
        (is (= 5003 (count expected)) "every official line was compared")))
    (is false "nestest not found in test-roms/nestest (or NESTEST_DIR)")))
