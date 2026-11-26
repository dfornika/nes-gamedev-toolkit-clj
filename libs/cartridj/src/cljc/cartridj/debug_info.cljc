(ns cartridj.debug-info
  "The names in a built game, saved as a file an emulator can read beside
  the .nes file: what the assembler called each place in the code, what
  the allocator put at each address in RAM, and what each subroutine and
  variable is for. With it, nes-cljs's inspectors say `STA player.x`
  instead of `STA $04`, and its Lens tab shows the game's variables.

  This is to the .nes file what ca65's .dbg file is to its ROMs: the
  assembler knows the names, the emulator only sees bytes, so the names
  travel separately, as data.

  The file is EDN:

    {:name        \"hopper.nes\"
     :labels      {:main 0x8012 ...}             ; from the assembler
     :memory      {:variables [{:name :kind :type :addr :size :doc} ...]
                   :symbols   {:player/x 0x04 ...}} ; from cartridj.memory/allocate
     :subroutines [{:name :doc} ...]}

  nes-cljs reads it in nes.debug-info.")

(defn data
  "The parts of `built` (what a game's `build` returns) that go in the
  file."
  [{:keys [name labels memory subroutines]}]
  (cond-> {:labels (or labels {})}
    name (assoc :name name)
    memory (assoc :memory (select-keys memory [:variables :symbols]))
    (seq subroutines) (assoc :subroutines (mapv #(select-keys % [:name :doc]) subroutines))))

(defn edn
  "`built`'s names as EDN text, to save beside its .nes file."
  [built]
  (pr-str (data built)))
