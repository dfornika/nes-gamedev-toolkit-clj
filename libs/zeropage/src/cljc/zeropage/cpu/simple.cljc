(ns zeropage.cpu.simple
  "A small 6502, written to be read: the CPU zeropage's test harness runs
  on, unless it's given another (see zeropage.cpu for the interface).

  It runs the 151 official instructions with the right cycle counts, and
  nothing else: no unofficial opcodes, and none of the extra 'dummy'
  reads a real 6502 makes. An emulator needs those, because on a console
  a read of a hardware register has effects (nes-cljs's nes.cpu.core has
  them). Code under test doesn't care.

  The registers are a Clojure map, and each instruction is a function from
  the registers before it to the registers after it (reading and writing
  memory through the bus as it goes). That makes it slower than a CPU
  built for speed, but still millions of instructions a second, which is
  plenty for tests.

    (def mem (atom {}))
    (def cpu (create {:read #(get @mem % 0) :write #(swap! mem assoc %1 %2)}))
    (cpu/set-registers! cpu {:pc 0x8000})
    (cpu/step! cpu)    ; => cycles, or nil if $8000 isn't an instruction"
  (:require [zeropage.cpu :as cpu :refer [C Z I D B U V N]]
            [zeropage.opcodes :as ops]))

;; ---------------------------------------------------------------------------
;; Flags

(defn- set-flag [regs bit on?]
  (update regs :p #(if on? (bit-or % bit) (bit-and % (bit-xor 0xFF bit)))))

(defn- set-nz
  "Set N and Z from `v`, a byte: N is its top bit, Z whether it's 0."
  [regs v]
  (-> regs
      (set-flag Z (zero? v))
      (set-flag N (pos? (bit-and v 0x80)))))

(defn- load
  "Put `v` in register `r`, setting N and Z from it (as every load,
  transfer and arithmetic instruction does)."
  [regs r v]
  (-> regs (assoc r v) (set-nz v)))

;; ---------------------------------------------------------------------------
;; Cycles

(defn- base-cycles
  "Cycles an instruction takes before the extra ones for crossing a page
  or taking a branch. They follow from how many bus cycles it needs:
  one per byte of the instruction, one per byte of data, plus a few for
  the work in between."
  [mnemonic mode]
  (case mnemonic
    (:sta :stx :sty) ({:zp 3 :zpx 4 :zpy 4 :abs 4 :abx 5 :aby 5 :izx 6 :izy 6} mode)
    (:asl :lsr :rol :ror :inc :dec) ({:acc 2 :zp 5 :zpx 6 :abs 6 :abx 7} mode)
    :jmp ({:abs 3 :ind 5} mode)
    (:jsr :rts :rti) 6
    :brk 7
    (:pha :php) 3
    (:pla :plp) 4
    ;; everything else: implied instructions, branches and reads
    ({:imp 2 :rel 2 :imm 2 :zp 3 :zpx 4 :zpy 4 :abs 4 :abx 4 :aby 4 :izx 6 :izy 5} mode)))

(def ^:private reads-with-page-penalty
  "Instructions that take an extra cycle when indexing crosses a page.
  (Stores and read-modify-writes always take it: it's in their base.)"
  #{:lda :ldx :ldy :adc :sbc :and :ora :eor :cmp})

;; ---------------------------------------------------------------------------
;; Memory

(defn- read16
  "A 2-byte address, low byte first, as the 6502 stores them."
  [read addr]
  (bit-or (read addr) (bit-shift-left (read (bit-and (inc addr) 0xFFFF)) 8)))

(defn- read16-zp
  "A 2-byte address from page 0, wrapping from $FF back to $00."
  [read zp]
  (bit-or (read zp) (bit-shift-left (read (bit-and (inc zp) 0xFF)) 8)))

(defn- crosses-page? [a b]
  (not= (bit-and a 0xFF00) (bit-and b 0xFF00)))

(defn- operand
  "Where instruction `mode`'s data is, for the instruction at `pc`:
  {:addr effective-address :crossed? did-indexing-cross-a-page}. For
  immediate mode the address is the operand byte itself; for relative
  (branches) it's the branch target."
  [{:keys [x y] :as regs} read mode pc next-pc]
  (let [b1 (read (bit-and (inc pc) 0xFFFF))
        w (fn [] (read16 read (bit-and (inc pc) 0xFFFF)))
        indexed (fn [base i]
                  (let [addr (bit-and (+ base i) 0xFFFF)]
                    {:addr addr :crossed? (crosses-page? base addr)}))]
    (case mode
      (:imp :acc) {:addr nil}
      :imm {:addr (bit-and (inc pc) 0xFFFF)}
      :zp {:addr b1}
      :zpx {:addr (bit-and (+ b1 x) 0xFF)}          ; stays in page 0
      :zpy {:addr (bit-and (+ b1 y) 0xFF)}
      :abs {:addr (w)}
      :abx (indexed (w) x)
      :aby (indexed (w) y)
      ;; JMP ($xxFF) takes its high byte from $xx00: the 6502 doesn't
      ;; carry into the pointer's high byte
      :ind (let [ptr (w)]
             {:addr (bit-or (read ptr)
                            (bit-shift-left (read (bit-or (bit-and ptr 0xFF00) (bit-and (inc ptr) 0xFF))) 8))})
      :izx {:addr (read16-zp read (bit-and (+ b1 x) 0xFF))}
      :izy (indexed (read16-zp read b1) y)
      :rel {:addr (bit-and (+ next-pc (if (< b1 0x80) b1 (- b1 256))) 0xFFFF)})))

;; ---------------------------------------------------------------------------
;; The stack: page 1, growing down

(defn- push [regs write v]
  (write (bit-or 0x100 (:sp regs)) v)
  (update regs :sp #(bit-and (dec %) 0xFF)))

(defn- pull
  "[regs-after value]"
  [regs read]
  (let [sp (bit-and (inc (:sp regs)) 0xFF)]
    [(assoc regs :sp sp) (read (bit-or 0x100 sp))]))

(defn- push16 [regs write v]
  (-> regs
      (push write (bit-shift-right v 8))
      (push write (bit-and v 0xFF))))

(defn- pull16 [regs read]
  (let [[regs lo] (pull regs read)
        [regs hi] (pull regs read)]
    [regs (bit-or lo (bit-shift-left hi 8))]))

(defn- pulled-p
  "P as PLP and RTI restore it: B isn't a real flag, and U is always 1."
  [v]
  (bit-or (bit-and v (bit-xor 0xFF B)) U))

;; ---------------------------------------------------------------------------
;; Arithmetic

(defn- adc
  "A + `v` + carry, setting C (carried out of bit 7) and V (the result's
  sign is wrong for signed numbers: two positives made a negative, or
  two negatives a positive)."
  [{:keys [a] :as regs} v]
  (let [sum (+ a v (if (cpu/flag? regs C) 1 0))
        result (bit-and sum 0xFF)]
    (-> regs
        (set-flag C (> sum 0xFF))
        (set-flag V (pos? (bit-and (bit-xor a result) (bit-xor v result) 0x80)))
        (load :a result))))

(defn- compare-with
  "CMP, CPX, CPY: subtract without keeping the result. C means
  register >= value."
  [regs r v]
  (let [diff (- (get regs r) v)]
    (-> regs
        (set-flag C (>= diff 0))
        (set-nz (bit-and diff 0xFF)))))

(defn- shift
  "ASL, LSR, ROL, ROR on `v`: [carry-out result]."
  [mnemonic regs v]
  (let [carry-in (if (cpu/flag? regs C) 1 0)]
    (case mnemonic
      :asl [(bit-shift-right v 7) (bit-and (bit-shift-left v 1) 0xFF)]
      :rol [(bit-shift-right v 7) (bit-and (bit-or (bit-shift-left v 1) carry-in) 0xFF)]
      :lsr [(bit-and v 1) (bit-shift-right v 1)]
      :ror [(bit-and v 1) (bit-or (bit-shift-right v 1) (bit-shift-left carry-in 7))])))

(def ^:private branch-flags
  "Each branch: the flag it tests, and whether it branches when it's set."
  {:bcc [C false] :bcs [C true] :bne [Z false] :beq [Z true]
   :bpl [N false] :bmi [N true] :bvc [V false] :bvs [V true]})

;; ---------------------------------------------------------------------------
;; One instruction

(defn- execute
  "Run the instruction `mnemonic` whose data is at `addr`, after the PC
  has moved on to `next-pc`. Returns [regs-after extra-cycles]."
  [mnemonic mode {:keys [a x y] :as regs} {:keys [read write]} addr next-pc]
  (let [regs (assoc regs :pc next-pc)
        m #(read addr)
        done (fn [regs] [regs 0])]
    (case mnemonic
      ;; loads and stores
      :lda (done (load regs :a (m)))
      :ldx (done (load regs :x (m)))
      :ldy (done (load regs :y (m)))
      :sta (do (write addr a) (done regs))
      :stx (do (write addr x) (done regs))
      :sty (do (write addr y) (done regs))

      ;; between registers
      :tax (done (load regs :x a))
      :tay (done (load regs :y a))
      :txa (done (load regs :a x))
      :tya (done (load regs :a y))
      :tsx (done (load regs :x (:sp regs)))
      :txs (done (assoc regs :sp x))             ; the one transfer that sets no flags

      ;; arithmetic and logic
      :adc (done (adc regs (m)))
      :sbc (done (adc regs (bit-xor (m) 0xFF)))   ; A - v - (1 - C) is A + ~v + C
      :and (done (load regs :a (bit-and a (m))))
      :ora (done (load regs :a (bit-or a (m))))
      :eor (done (load regs :a (bit-xor a (m))))
      :cmp (done (compare-with regs :a (m)))
      :cpx (done (compare-with regs :x (m)))
      :cpy (done (compare-with regs :y (m)))
      :bit (let [v (m)]
             (done (-> regs
                       (set-flag Z (zero? (bit-and a v)))
                       (set-flag N (pos? (bit-and v 0x80)))
                       (set-flag V (pos? (bit-and v 0x40))))))

      ;; counting
      :inx (done (load regs :x (bit-and (inc x) 0xFF)))
      :iny (done (load regs :y (bit-and (inc y) 0xFF)))
      :dex (done (load regs :x (bit-and (dec x) 0xFF)))
      :dey (done (load regs :y (bit-and (dec y) 0xFF)))
      :inc (let [v (bit-and (inc (m)) 0xFF)] (write addr v) (done (set-nz regs v)))
      :dec (let [v (bit-and (dec (m)) 0xFF)] (write addr v) (done (set-nz regs v)))

      ;; shifts and rotates, on A or on memory
      (:asl :lsr :rol :ror)
      (let [[carry v] (shift mnemonic regs (if (= mode :acc) a (m)))
            regs (set-flag regs C (= 1 carry))]
        (if (= mode :acc)
          (done (load regs :a v))
          (do (write addr v) (done (set-nz regs v)))))

      ;; flags
      :clc (done (set-flag regs C false))
      :sec (done (set-flag regs C true))
      :cli (done (set-flag regs I false))
      :sei (done (set-flag regs I true))
      :cld (done (set-flag regs D false))
      :sed (done (set-flag regs D true))
      :clv (done (set-flag regs V false))

      ;; branches: 1 extra cycle if taken, 2 if that lands on another page
      (:bcc :bcs :bne :beq :bpl :bmi :bvc :bvs)
      (let [[bit when-set?] (branch-flags mnemonic)]
        (if (= when-set? (cpu/flag? regs bit))
          [(assoc regs :pc addr) (if (crosses-page? next-pc addr) 2 1)]
          (done regs)))

      ;; jumps and subroutines
      :jmp (done (assoc regs :pc addr))
      ;; JSR pushes the address of its own last byte; RTS adds the 1 back
      :jsr (done (-> regs (push16 write (bit-and (dec next-pc) 0xFFFF)) (assoc :pc addr)))
      :rts (let [[regs ret] (pull16 regs read)]
             (done (assoc regs :pc (bit-and (inc ret) 0xFFFF))))
      ;; BRK is an interrupt the program asks for: it skips a byte, and
      ;; pushes P with B set so the handler can tell it from an IRQ
      :brk (done (-> regs
                     (push16 write (bit-and (inc next-pc) 0xFFFF))
                     (push write (bit-or (:p regs) B U))
                     (set-flag I true)
                     (assoc :pc (read16 read 0xFFFE))))
      :rti (let [[regs p] (pull regs read)
                 [regs ret] (pull16 regs read)]
             (done (assoc regs :p (pulled-p p) :pc ret)))

      ;; the stack
      :pha (done (push regs write a))
      :php (done (push regs write (bit-or (:p regs) B U)))
      :pla (let [[regs v] (pull regs read)] (done (load regs :a v)))
      :plp (let [[regs v] (pull regs read)] (done (assoc regs :p (pulled-p v))))

      :nop (done regs))))

(defn- step [regs {:keys [read] :as bus}]
  (let [pc (:pc regs)]
    (when-let [{:keys [mnemonic mode size]} (ops/opcodes (read pc))]
      (let [next-pc (bit-and (+ pc size) 0xFFFF)
            {:keys [addr crossed?]} (operand regs read mode pc next-pc)
            [regs extra] (execute mnemonic mode regs bus addr next-pc)]
        [regs (+ (base-cycles mnemonic mode)
                 (if (and crossed? (reads-with-page-penalty mnemonic)) 1 0)
                 extra)]))))

;; ---------------------------------------------------------------------------
;; The CPU

(def power-on
  "The registers when the CPU is switched on, before RESET has run."
  {:a 0 :x 0 :y 0 :sp 0xFD :pc 0 :p 0x24})

(defn create
  "A CPU on `bus` ({:read (fn [addr]) :write (fn [addr byte])}), with its
  registers as at power on. See zeropage.cpu/Cpu for what it does."
  [bus]
  (let [regs (volatile! power-on)]
    (reify cpu/Cpu
      (registers [_] @regs)
      (set-registers! [this new-regs] (vswap! regs merge new-regs) this)
      (step! [_]
        (when-let [[after cycles] (step @regs bus)]
          (vreset! regs after)
          cycles))
      (nmi! [_]
        (vreset! regs (-> @regs
                          (push16 (:write bus) (:pc @regs))
                          (push (:write bus) (bit-and (bit-or (:p @regs) U) (bit-xor 0xFF B)))
                          (set-flag I true)
                          (assoc :pc (read16 (:read bus) 0xFFFA))))
        7)
      (jump-to-reset-vector! [_]
        (vswap! regs assoc :pc (read16 (:read bus) 0xFFFC))
        7))))
