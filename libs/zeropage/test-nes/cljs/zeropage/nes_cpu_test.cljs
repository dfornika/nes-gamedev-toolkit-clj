(ns zeropage.nes-cpu-test
  "Switches the harness to nes-cljs's CPU (nes.cpu.zeropage), so every
  other test in this build (asm, gen, nestest, ...) runs on it instead of
  zeropage's simple one. Only in the :test-nes build (npm run
  test:node-nes), which has nes-cljs on its classpath; the tests find
  `harness/*cpu*` when they run, after every namespace has loaded."
  (:require [cljs.test :refer [deftest is]]
            [nes.cpu.zeropage :as nes-cpu]
            [zeropage.harness :as harness]))

(set! harness/*cpu* nes-cpu/create)

(deftest the-harness-runs-on-nes-cljs-s-cpu
  (is (identical? nes-cpu/create harness/*cpu*)))
