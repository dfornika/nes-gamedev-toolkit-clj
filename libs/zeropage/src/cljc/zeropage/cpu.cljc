(ns zeropage.cpu
  "What zeropage needs from a 6502 to run the code it makes: an interface
  any CPU can implement, so that the test harness (zeropage.harness) can
  run on whichever one you choose.

  zeropage comes with one CPU, zeropage.cpu.simple: small, and meant to be
  read. Others can be plugged in. nes-cljs's accurate CPU (nes.cpu.core,
  the one its emulator runs) implements this interface in
  nes.cpu.zeropage. Running the same code on two CPUs and comparing them
  catches bugs in either one.

  A CPU is made from a *bus*: how it reaches memory. A bus is a map of
  two functions:

    {:read  (fn [addr] byte)        ; 0-255, from addresses 0-$FFFF
     :write (fn [addr byte] ...)}

  and a CPU implementation is a function from a bus to something that
  implements `Cpu`, like zeropage.cpu.simple/create.

  The registers are a map (see `registers`):

    {:a 0 :x 0 :y 0     ; accumulator, X and Y index registers
     :sp 0xFD           ; stack pointer: the stack is $0100-$01FF
     :pc 0x8000         ; program counter: the next instruction's address
     :p 0x24}           ; status flags, one per bit: N V - B D I Z C

  What an implementation must do, and what it may do differently:

  - The 151 official instructions, with their effects on registers,
    flags and memory exactly as a 6502 has them (without decimal mode,
    as on the NES's 2A03: D can be set, but ADC and SBC ignore it).
  - Cycle counts as a 6502 takes them, with the extra cycle when an
    indexed read crosses a page and for a branch taken (plus one more if
    it crosses a page). Tests check these, to know a routine fits in a
    frame.
  - The 6502's quirks that code can see: JMP ($xxFF) reads its high byte
    from $xx00, zero-page indexing wraps within page 0, BRK and PHP push
    P with B set, interrupts push it with B clear.
  - Unofficial opcodes are optional. A CPU that doesn't run one returns
    nil from `step!` and leaves everything as it was.
  - Dummy reads and writes (the extra bus cycles a real 6502 makes) are
    optional, and nothing in zeropage relies on them.")

;; Status flag bits, in P
(def C 0x01)   ; carry
(def Z 0x02)   ; zero
(def I 0x04)   ; interrupt disable
(def D 0x08)   ; decimal (no effect on the NES)
(def B 0x10)   ; 'break': only in copies of P pushed to the stack
(def U 0x20)   ; unused, always 1
(def V 0x40)   ; overflow
(def N 0x80)   ; negative

(defprotocol Cpu
  (registers [cpu]
    "The registers now, as a map: {:a :x :y :sp :pc :p}.")
  (set-registers! [cpu regs]
    "Change some registers: (set-registers! cpu {:pc 0x8000 :a 1}).
    Others keep their values. Returns `cpu`.")
  (step! [cpu]
    "Run one instruction, the one at PC. Returns the cycles it took, or
    nil, changing nothing, if the byte at PC isn't an instruction this
    CPU runs.")
  (nmi! [cpu]
    "Take a non-maskable interrupt: push PC and P (with B clear), set I,
    and jump to the address at $FFFA. Returns the cycles it took (7).")
  (jump-to-reset-vector! [cpu]
    "Go to the address at $FFFC, as the CPU does when it's switched on.
    Returns the cycles it took (7)."))

(defn flag?
  "Is `bit` (one of C Z I D B U V N) set in `regs`'s P?"
  [regs bit]
  (pos? (bit-and (:p regs) bit)))
