(ns zeropage.cpu.simple-test
  "The simple CPU's instructions, a few at a time. (nestest checks them
  all: see zeropage.nestest-test.)"
  (:require [cljs.test :refer [deftest is testing]]
            [zeropage.cpu :as cpu :refer [C V N I B]]
            [zeropage.cpu.simple :as simple]))

(defn- machine
  "A simple CPU at $8000 over memory holding `program` there, with the
  NMI vector pointing at $9000 and IRQ/BRK at $A000. Returns [cpu mem],
  where mem is an atom of {addr byte}."
  [program]
  (let [mem (atom (merge (zipmap (range 0x8000 0x10000) (concat program (repeat 0)))
                         {0xFFFA 0x00 0xFFFB 0x90 0xFFFE 0x00 0xFFFF 0xA0}))
        c (simple/create {:read #(get @mem % 0) :write #(swap! mem assoc %1 %2)})]
    (cpu/set-registers! c {:pc 0x8000})
    [c mem]))

(defn- run
  "Step `n` instructions. Returns [registers cycles]."
  [c n]
  (let [cycles (reduce + (repeatedly n #(cpu/step! c)))]
    [(cpu/registers c) cycles]))

(deftest power-on
  (is (= {:a 0 :x 0 :y 0 :sp 0xFD :pc 0 :p 0x24} simple/power-on)))

(deftest adding
  (testing "carry and overflow"
    (let [[c] (machine [0xA9 0x7F 0x18 0x69 0x01])   ; LDA #$7F, CLC, ADC #$01
          [r cycles] (run c 3)]
      (is (= 0x80 (:a r)))
      (is (cpu/flag? r V) "two positives made a negative")
      (is (cpu/flag? r N))
      (is (not (cpu/flag? r C)))
      (is (= 6 cycles))))
  (testing "subtracting borrows through the carry"
    (let [[c] (machine [0xA9 0x05 0x38 0xE9 0x07])   ; LDA #5, SEC, SBC #7
          [r] (run c 3)]
      (is (= 0xFE (:a r)))
      (is (not (cpu/flag? r C)) "C clear: it borrowed"))))

(deftest page-crossing-costs-a-cycle
  (let [[c] (machine [0xA2 0x01 0xBD 0xFF 0x00 0xBD 0x00 0x00])]  ; LDX #1, LDA $00FF,X, LDA $0000,X
    (is (= 2 (cpu/step! c)))
    (is (= 5 (cpu/step! c)) "$00FF + 1 is on the next page")
    (is (= 4 (cpu/step! c)))))

(deftest branches
  (let [[c] (machine [0xA9 0x00 0xF0 0x02 0x00 0x00 0xD0 0xFE])]  ; LDA #0, BEQ +2, (skipped), BNE -2
    (cpu/step! c)
    (is (= 3 (cpu/step! c)) "taken: one more cycle")
    (is (= 0x8006 (:pc (cpu/registers c))))
    (is (= 2 (cpu/step! c)) "not taken")
    (is (= 0x8008 (:pc (cpu/registers c))))))

(deftest subroutines-and-the-stack
  (let [[c mem] (machine [0x20 0x05 0x80 0xEA 0xEA 0x48 0x68 0x60])]   ; JSR $8005 ... PHA, PLA, RTS
    (cpu/set-registers! c {:a 0x42})
    (is (= 6 (cpu/step! c)))
    (is (= [0x80 0x02] [(@mem 0x1FD) (@mem 0x1FC)]) "JSR pushes the address of its last byte")
    (is (= 3 (cpu/step! c)))
    (is (= 0x42 (@mem 0x1FB)) "PHA")
    (is (= 4 (cpu/step! c)) "PLA")
    (is (= 6 (cpu/step! c)))
    (is (= 0x8003 (:pc (cpu/registers c))) "RTS returns past the JSR")
    (is (= 0xFD (:sp (cpu/registers c))))))

(deftest interrupts
  (let [[c mem] (machine [0x00])]
    (testing "NMI pushes PC and P with B clear, and sets I"
      (is (= 7 (cpu/nmi! c)))
      (let [r (cpu/registers c)]
        (is (= 0x9000 (:pc r)))
        (is (cpu/flag? r I))
        (is (zero? (bit-and B (@mem 0x1FB))))))
    (testing "BRK pushes P with B set, and skips a byte"
      (cpu/set-registers! c {:pc 0x8000 :sp 0xFD})
      (is (= 7 (cpu/step! c)))
      (is (= 0xA000 (:pc (cpu/registers c))))
      (is (pos? (bit-and B (@mem 0x1FB))))
      (is (= [0x80 0x02] [(@mem 0x1FD) (@mem 0x1FC)])))))

(deftest jmp-indirect-has-the-page-bug
  (let [[c mem] (machine [0x6C 0xFF 0x02])]           ; JMP ($02FF)
    (swap! mem assoc 0x02FF 0x34 0x0300 0x56 0x0200 0x12)
    (cpu/step! c)
    (is (= 0x1234 (:pc (cpu/registers c))) "the high byte comes from $0200, not $0300")))

(deftest unofficial-opcodes-are-not-run
  (let [[c] (machine [0xA7 0x10])]                    ; LAX $10
    (is (nil? (cpu/step! c)))
    (is (= 0x8000 (:pc (cpu/registers c))) "nothing changed")))
