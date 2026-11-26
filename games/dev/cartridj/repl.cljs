(ns cartridj.repl
  "The REPL for building games and watching them run in nes-cljs's app:
  everything at hand in one namespace. The :app build (see
  shadow-cljs.edn) starts its REPL here.

    npm run dev                        ; build and serve http://localhost:9500
    npx shadow-cljs cljs-repl app      ; (with the page open) a REPL in it

    (play! (catch.core/build))         ; build it, run it, with names and overlays
    (regs) (dis) (break! :write :player/x) (rd :player/x) (look)
    (save! (catch.core/build))         ; download the .nes
    (save-names! (catch.core/build))   ; ...and its names file

  The inspecting tools are nes-cljs's (nes.repl); `play!` and the saving
  functions are from cartridj.workbench. Each game's namespace is loaded
  here too, so `(<game>.core/build)` works for every game."
  (:require [cartridj.workbench :refer [play! save! save-names! save-edn!]]
            [nes.repl :refer [hex sym syms rd rd-ppu poke! regs pause! resume! speed!
                              step! break! breaks unbreak! why continue! line! frame!
                              press! dis hexdump snap diff search print-diff rom lenses
                              register-lens! lens! current-lens look lens-source
                              screen-text rom-poke! record-code! code-summary tap-snap!
                              load! tile tile! overlays overlay! overlay-on! overlay-off!]]
            ;; every game, so its `build` is there to call
            [alienscape.core]
            [catch.core]
            [hello.core]
            [hopper.core]
            [ladder.core]
            [platform-kit.core]
            [star-run.core]
            [tower.core]))
