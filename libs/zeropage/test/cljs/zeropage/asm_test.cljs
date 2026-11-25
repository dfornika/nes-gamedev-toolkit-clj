(ns zeropage.asm-test
  (:require [cljs.test :refer [deftest is testing]]
            [zeropage.harness :as harness]))

(deftest running-assembled-code
  (testing "adding, with a symbol for the variable"
    (let [r (harness/run-routine [[:lda [:imm 2]] [:clc] [:adc :n] [:sta :n] [:rts]]
                                 {:symbols {:n 0x10} :memory {0x10 40}})]
      (is (= 42 (:a r)))
      (is (= 42 ((:peek r) 0x10)))
      (is (= (+ 2 2 3 3 6) (:cycles r)))))
  (testing "a generator: a 16-bit add, checked against +"
    (let [add16 (fn [dst src]
                  [[:clc]
                   [:lda dst] [:adc src] [:sta dst]
                   [:lda [:+ dst 1]] [:adc [:+ src 1]] [:sta [:+ dst 1]]])]
      (doseq [[a b] [[0 0] [1 2] [0x00FF 0x0001] [0x1234 0x0FF0] [0xFFFF 0x0001]]]
        (let [r (harness/run-routine [(add16 :a16 :b16) [:rts]]
                                     {:symbols {:a16 0x10 :b16 0x12}
                                      :memory {0x10 [(bit-and a 0xFF) (bit-shift-right a 8)]
                                               0x12 [(bit-and b 0xFF) (bit-shift-right b 8)]}})]
          (is (= (bit-and (+ a b) 0xFFFF)
                 (+ ((:peek r) 0x10) (* 256 ((:peek r) 0x11))))
              (str a " + " b))))))
  (testing "a loop, a subroutine and registers on entry"
    (let [r (harness/run-routine [[:label :loop] [:jsr :bump] [:dex] [:bne :loop] [:rts]
                                  [:label :bump] [:inc 0x20] [:rts]]
                                 {:x 5})]
      (is (= 5 ((:peek r) 0x20)))
      (is (:zero? r))))
  (testing "code that never returns is caught"
    (is (thrown-with-msg? js/Error #"Still running"
                          (harness/run-routine [[:label :forever] [:jmp :forever]] {:max-cycles 1000})))))