# zeropage's CPU interface

zeropage runs the code it makes, to test it. It does that through a small
interface, `zeropage.cpu/Cpu`, so the CPU underneath can be swapped. It
ships one CPU of its own, and nes-cljs provides another.

## Why there are two CPUs

The two have different jobs:

- **`zeropage.cpu.simple`** (in zeropage) runs code under test. It needs
  the right results and the right cycle counts, and it should be easy to
  read. It's about 300 lines: the registers are a Clojure map, and each
  instruction is a function from the registers before it to the
  registers after it.
- **`nes.cpu.core`** (in nes-cljs) runs games inside an emulator. It also
  has to make every extra "dummy" bus read a real 6502 makes, in the same
  order as jsnes, because on a console a stray read of a PPU register
  changes the picture. It also handles the DMC DMA quirk, the unofficial
  opcodes and exact NMI timing. None of that is zeropage's business, so
  it stays in nes-cljs.

Neither project depends on the other's CPU. nes-cljs depends on zeropage
(for the opcode table, the disassembler and this interface). zeropage
depends on nothing.

## The interface (`zeropage.cpu`, .cljc)

A **bus** is how a CPU reaches memory: a map of two functions.

```clojure
{:read  (fn [addr] byte)       ; addr 0-$FFFF, byte 0-255
 :write (fn [addr byte] ...)}
```

A **CPU implementation** is a function from a bus to a `Cpu`, such as
`zeropage.cpu.simple/create` or `nes.cpu.zeropage/create`.

```clojure
(defprotocol Cpu
  (registers [cpu])              ; => {:a :x :y :sp :pc :p}
  (set-registers! [cpu regs])    ; merge some in; returns cpu
  (step! [cpu])                  ; one instruction => cycles, or nil (changing nothing)
                                 ;   if the byte at PC isn't one this CPU runs
  (nmi! [cpu])                   ; push PC and P (B clear), set I, jump via $FFFA => 7
  (jump-to-reset-vector! [cpu])) ; jump via $FFFC => 7
```

The flag bits (`C Z I D B U V N`) and `(flag? regs bit)` are in the same
namespace.

### What an implementation must do

- Run the 151 official instructions with a 6502's effects on registers,
  flags and memory, without decimal mode (as on the NES's 2A03: D can be
  set, but ADC and SBC ignore it).
- Take a 6502's cycle counts, including the extra cycle when an indexed
  read crosses a page, and the extra cycle for a branch taken (two if it
  lands on another page). Tests use these to check that a game's work fits
  in a frame.
- Have the quirks code can see:
  - JMP ($xxFF) takes its high byte from $xx00.
  - Zero-page indexing and (zp) pointers wrap within page 0.
  - BRK and PHP push P with B set; interrupts push it with B clear.
  - PLP and RTI clear B and set U.

### What it may do differently

- **Unofficial opcodes:** optional. If it doesn't run one, `step!`
  returns nil.
- **Dummy reads and writes:** optional. Nothing in zeropage relies on
  them.
- **A B bit set in P itself:** a real 6502 can't get into that state (B
  only exists in the copies of P pushed to the stack), so what happens
  from there is undefined. nes-cljs's CPU keeps the bit through RTI and
  the simple CPU clears it. The comparison test in nes-cljs found this
  when its random starting states included it; it now starts only from
  states a 6502 can reach.

## How the harness chooses

`zeropage.harness` (in `test/cljs`) uses `*cpu*`, which is
`zeropage.cpu.simple/create` by default. You can override it three ways:

```clojure
(harness/run-routine forms {:cpu nes.cpu.zeropage/create})   ; one call
(harness/machine bytes org {:cpu ...})                       ; one machine
(binding [harness/*cpu* nes.cpu.zeropage/create] ...)        ; a block of tests
```

`machine` returns `{:mem :cpu :bus}`, and `(harness/pc m)` reads the
program counter. `machine` also takes `:bus`: a function from the flat
64 KB bus to the one the CPU gets, for putting hardware in front of some
addresses (as `console` does for the controller and PPUSTATUS).

## How each CPU is checked

- **Simple CPU, by itself:** `zeropage.cpu.simple-test` checks a few
  instructions at a time. `zeropage.nestest-test` runs nestest's official
  section (the first 5,003 lines of the log, every register and the cycle
  count before each instruction) on `harness/*cpu*`.
- **nes-cljs's CPU:** nes-cljs keeps its own full nestest run (all 8,991
  lines, unofficial opcodes included) and blargg's ROMs.
- **The two against each other:** `nes.cpu.zeropage-test` in nes-cljs
  runs 300 random programs (5,000 were also tried once). Each starts with
  random registers and a random zero page, and runs on both CPUs. After
  every instruction the registers and cycles must match, and at the end
  the whole 64 KB of memory must too. This lives in nes-cljs because
  nes-cljs can see both CPUs and zeropage can't.

## Still to do (integration)

1. **Commit and push zeropage.** This changes `libs/zeropage`: `src/cljc/zeropage/cpu.cljc`,
   `src/cljc/zeropage/cpu/simple.cljc`, `test/cljs/zeropage/harness.cljs`,
   `test/cljs/zeropage/cpu/simple_test.cljs`, `test/cljs/zeropage/nestest_test.cljs`,
   `test-roms/nestest/`, `deps.edn` (no more nes-cljs in `:test`) and
   `shadow-cljs.edn`. It also changes `games/ladder/test` (CPU calls through the interface) and
   `games/deps.edn` (comment). The accurate CPU that was briefly copied
   here (`src/cljs/zeropage/cpu.cljs`) is deleted again.
2. **Point nes-cljs at the pushed zeropage.** nes-cljs is on branch `cpu-to-zeropage`. Its `deps.edn`
   uses `{:local/root "../nes-gamedev-toolkit-clj/libs/zeropage"}`, which
   CI and GitHub Pages can't see. Once zeropage is pushed, switch it to:
   ```clojure
   io.github.dfornika/zeropage {:git/url "https://github.com/dfornika/nes-gamedev-toolkit-clj"
                                :git/sha "<sha>" :deps/root "libs/zeropage"}
   ```
   Then push the branch and open the PR.
3. **Optional: run zeropage's own tests on nes-cljs's CPU.** Add an alias
   or build on the toolkit side with nes-cljs on its classpath and a
   preload that does
   `(set! zeropage.harness/*cpu* nes.cpu.zeropage/create)`. The same
   asm, gen and game tests then run on the emulator's CPU. It doesn't need
   to run by default.
