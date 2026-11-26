(ns cartridj.workbench
  "Where games built with cartridj meet nes-cljs's console, at the REPL:
  build a game, put the cartridge in, and tell the inspectors what the
  game calls things. (Moved here from nes-cljs's nes.workbench, which no
  longer builds games: the emulator only plays and inspects them.)

    (play! (catch.core/build))     ; build and run, with names and overlays
    (save! (catch.core/build))     ; download it as a .nes file
    (save-names! (catch.core/build)) ; ...and its names file
    (save-edn! :source)            ; the loaded game's program, as EDN text

  Needs nes-cljs's app running in the page (the :dev alias puts nes-cljs
  on the classpath). Not wired into a shadow-cljs build yet."
  (:require [cartridj.debug-info :as names]
            [cartridj.rom-edn :as rom-edn]
            [nes.app.console :as console]
            [nes.app.current :as current]
            [nes.inspect.debug-info :as debug-info]
            [nes.emulator :as emu]
            [nes.inspect.overlay :as overlay]
            [nes.rom :as rom]))

;; What `build` returned for the games played here, by ROM hash, so their
;; source can be saved later. {sha1 built}
(defonce ^:private built-games (atom {}))

(defn install!
  "Tell nes-cljs what's known about a built game (what its `build`
  returns), under its ROM's hash: its names (labels and variables, the
  same data its names file holds: see cartridj.debug-info) and its
  overlays (see nes.inspect.overlay). Overlays are functions, so they can't go in
  a names file: only games played from here have them. Returns the ROM as
  a Uint8Array."
  [built]
  (let [bytes (js/Uint8Array.from (clj->js (:rom built)))
        sha1 (rom/rom-hash bytes)
        names (names/data built)
        fields (debug-info/lens-fields (:memory names))]
    (debug-info/install! sha1 names)
    (when (:source built) (swap! built-games assoc sha1 built))
    ;; the game's own overlays read its variables
    (doseq [o (:overlays built)]
      (overlay/register! (merge {:fields fields} o {:rom sha1})))
    bytes))

(defn play!
  "Put `built` (a map with :rom, the .nes file's bytes, and maybe :name,
  :labels and :memory; or just the bytes) into the console and start it.
  Returns the ROM's size in bytes."
  [built]
  (let [built (if (map? built) built {:rom built})]
    (console/load-bytes! (or (:name built) "built.nes") (install! built))
    (count (:rom built))))

;; ---------------------------------------------------------------------------
;; Saving cartridges as files

(defn- download!
  "Have the browser save `content` (text, or a Uint8Array) as `file-name`."
  [file-name content]
  (let [url (js/URL.createObjectURL
             (js/Blob. #js [content]
                       #js {:type (if (string? content) "text/plain" "application/octet-stream")}))
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) file-name)
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
    file-name))

(defn- base-name [rom-name]
  (.replace (or rom-name "game.nes") (js/RegExp. "\\.nes$" "i") ""))

(defn save!
  "Download a built game (what its `build` returned) as a .nes file."
  [built]
  (download! (or (:name built) "built.nes") (js/Uint8Array.from (clj->js (:rom built)))))

(defn save-names!
  "Download a built game's names file, to put beside its .nes file."
  [built]
  (download! (str (base-name (:name built)) ".debug.edn") (names/edn built)))

(defn edn-text
  "The loaded cartridge as EDN text (see cartridj.rom-edn). `kind` is
  :source (its program as instructions and its tiles as drawings: only
  for games played from here) or :container (its bytes as hex: any ROM)."
  [kind]
  (let [file (some-> (current/emulator-instance) emu/rom-file array-seq vec)
        sha1 (some-> file clj->js js/Uint8Array.from rom/rom-hash)]
    (case kind
      ;; with the tiles as they are now, in case they've been painted on
      ;; in the Tiles tab since the game was built
      :source (when-let [built (get @built-games sha1)]
                (let [chr-size (count (get-in built [:source :chr]))
                      built (cond-> built
                              (pos? chr-size)
                              (assoc-in [:source :chr] (subvec file (- (count file) chr-size))))]
                  (rom-edn/write (rom-edn/source built))))
      :container (some-> file rom-edn/container rom-edn/write))))

(defn save-edn!
  "Download the loaded cartridge as an .edn file: (save-edn! :source) or
  (save-edn! :container); with no argument, the source if there is one."
  ([] (or (save-edn! :source) (save-edn! :container)))
  ([kind]
   (when-let [text (edn-text kind)]
     (download! (str "game" (when (= kind :container) ".rom") ".edn") text))))
