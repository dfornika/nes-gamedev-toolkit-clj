(ns cartridj.palette
  "Colors and palettes, by name.

  The NES can show 64 colors (really about 54: some repeat). A color is a
  number from $00 to $3F: the low digit is the hue, the high digit how
  light it is. `colors` gives them names: :red, :dark-red, :light-red,
  :pale-red, and so on round the color wheel.

  What a game can use at once is far less: a backdrop color, and eight
  palettes of three colors each, four for the background and four for
  sprites. Every tile or sprite is drawn with one palette, its pixels
  numbered 1 to 3 picking from it, and 0 showing the backdrop (or, in a
  sprite, whatever is behind it).

    (palettes {:backdrop   :dark-blue
               :background [[:white :gray :dark-gray]]
               :sprites    [[:black :yellow :white]
                            [:orange :pale-yellow :white]]})

  gives the 32 bytes the PPU wants. Palettes left out are black."
  (:require [clojure.string :as str]))

(def ^:private hues
  ;; the low digit of a color number -> what to call it
  ;; names from https://www.nesdev.org/wiki/PPU_palettes
  {0x1 "azure" 
   0x2 "blue" 
   0x3 "violet" 
   0x4 "magenta" 
   0x5 "rose" 
   0x6 "red" 
   0x7 "orange"
   0x8 "yellow" 
   0x9 "chartreuse" 
   0xA "green" 
   0xB "spring" 
   0xC "cyan"})

(def ^:private shades
  {0x00 "dark"
   0x01 ""
   0x02 "light"
   0x03 "pale"})

(def colors
  "Name -> color number. Each hue comes in four: :dark-<hue>, :<hue>, :light-<hue>, :pale-<hue>.

   Plus: :black, :white, :blacker-than-black
         :dark-gray, :gray, :light-gray,
         :brown, :dark-brown, 
         :pink"
  (into {:blacker-than-black 0x0D :black 0x0F :white      0x30
         :dark-gray 0x00          :gray  0x10 :light-gray 0x3D  
         :brown     0x17          :dark-brown 0x07
         :pink      0x25
         :skintone  0x36      :skintone-2 0x37}
        (for [[digit hue] hues
              [row shade] [[0 "dark-"] [1 ""] [2 "light-"] [3 "pale-"]]]
          [(keyword (str shade hue)) (+ (* 16 row) digit)])))

(def colors-by-idx
  [:dark-gray    :dark-azure       :dark-blue   :dark-violet  :dark-magenta  :dark-rose          :dark-red  :dark-brown 
   :dark-yellow  :dark-chartreuse  :dark-green  :dark-spring  :dark-cyan     :blacker-than-black :black     :black
   
   :gray         :azure            :blue        :violet       :magenta       :rose               :red       :brown
   :yellow       :chartreuse       :green       :spring       :cyan          :black              :black     :black

   :light-gray   :light-azure      :light-blue  :light-violet :light-magenta :light-rose         :light-red :light-orange
   :light-yellow :light-chartreuse :light-green :light-spring :light-cyan    :black              :black     :black
   
   ])

(def rgb-palette-jsnes
  "What each of the 64 color numbers looks like, as [red green blue], 0
  to 255 each. The NES made its colors as a TV signal, not as red, green
  and blue, so every emulator's table is someone's measurement and they
  all differ a little; this is the one jsnes draws with."
  [[ 82  82  82] [  0   0 180] [  0   0 160] [ 61   0 177] [105   0 116] [ 91   0   0] [ 95   0   0] [ 64  24   0]    ;; :dark
   [ 16  47   0] [  8  74   8] [  0 103   0] [  0  66  18] [  0  40 109] [  0   0   0] [  0   0   0] [  0   0   0]    ;; :dark

   [231 213 196] [  0  64 255] [ 34  14 220] [107  71 255] [159   0 215] [215  10 104] [188  25   0] [177  84   0]    ;;
   [ 91 106   0] [  3 140   0] [  0 171   0] [  0 136  44] [  0 114 164] [  0   0   0] [  0   0   0] [  0   0   0]    ;;

   [248 248 248] [ 60 171 255] [129 121 255] [197  91 255] [242  72 255] [255  73 223] [255 109  71] [247 180   0]    ;; light
   [255 224   0] [117 227   0] [ 43 244   3] [ 46 184 120] [ 24 226 229] [120 120 120] [  0   0   0] [  0   0   0]    ;; light

   [255 255 255] [190 242 255] [184 184 248] [216 184 248] [255 182 255] [255 195 255] [255 209 199] [255 218 154]    ;; pale
   [248 237 136] [221 255 131] [184 248 184] [172 248 245] [176 255 255] [248 216 248] [  0   0   0] [  0   0   0]])  ;; pale

(def rgb-palette-aseprite
  "The nes palette from asperite (remapped to standard indexes)"
  [[121 121 121] [ 32   0 178] [ 40   0 186] [ 97  16 162] [154  32 121] [178  16  48] [162  48   0] [121  65   0]
   [ 73  89   0] [ 56  105  0] [ 56 109   0] [ 48  97  65] [ 48  81 130] [  0   0   0] [  0   0   0] [  0   0   0]

   [178 178 178] [ 65  97 251] [ 65  65 255] [146  65 243] [219  65 195] [219  65  97] [227  81   0] [195 113   0]
   [138 138   0] [ 81 162   0] [ 73 170  16] [ 73 162 105] [ 65 146 195] [  0   0   0] [  0   0   0] [  0   0   0]
   
   [235 235 235] [ 97 162 255] [ 81 130 255] [162 113 255] [243  97 255] [255  97 178] [255 121  48] [255 162   0]
   [235 211  32] [154 235   0] [113 243  65] [113 227 146] [ 97 211 227] [121 121 121] [  0   0   0] [  0   0   0]
   
   [255 255 255] [146 211 255] [162 186 255] [195 178 255] [227 178 255] [255 186 235] [255 203 186] [255 219 162]
   [255 243 146] [203 243 130] [162 243 162] [162 255 203] [162 255 243] [162 162 162] [  0   0   0] [  0   0   0]])


(defn nearest
  "The color number that looks most like [red green blue]. Black is $0F
  (the blacks at $0D and $0E are best avoided: $0D is 'blacker than black'
  and upsets some TVs)."
  ([[r g b] rgb-palette]
   (if (= [0 0 0] [r g b])
     0x0F
     (apply min-key
            (fn [n] (let [[r2 g2 b2] (rgb-palette n)]
                      (+ (* (- r r2) (- r r2)) (* (- g g2) (- g g2)) (* (- b b2) (- b b2)))))
            ;; the last three of each row repeat black
            (for [n (range 64) :when (< (mod n 16) 13)] n))))
  ([rgb]
   (nearest rgb rgb-palette-jsnes)))

(comment 
  (nearest (nth rgb-palette-aseprite 7))
  )

(defn color
  "The number of a color: a name from `colors`, or a number 0 to 63."
  [c]
  (cond
    (and (int? c) (<= 0 c 0x3F)) c
    (contains? colors c) (colors c)
    :else (throw (ex-info (str "Unknown color " (pr-str c) ". Colors are numbers 0 to 63, or names like "
                               (str/join ", " (map pr-str [:black :white :gray :red :dark-red :light-red :pale-red :blue :green :yellow]))
                               " (see cartridj.palette/colors)")
                          {:color c}))))

(comment 
  (color :brown)
  (format "%X" (color :brown))
  (color :red)
  (format "%X" (color :red))
  )

(defn- four-palettes [backdrop palettes what]
  (when (> (count palettes) 4)
    (throw (ex-info (str "There are four " what " palettes, not " (count palettes)) {})))
  (mapcat (fn [p]
            (when-not (= 3 (count p))
              (throw (ex-info (str "A palette is three colors (color 0 is the backdrop), not " (pr-str p)) {:palette p})))
            (cons backdrop (map color p)))
          (take 4 (concat palettes (repeat [:black :black :black])))))

(defn palettes
  "The 32 bytes of palette memory, from {:backdrop color, :background
  [[c1 c2 c3] ...], :sprites [[c1 c2 c3] ...]} (up to four palettes of
  each kind)."
  [{:keys [backdrop background sprites] :or {backdrop :black}}]
  (let [backdrop (color backdrop)]
    (vec (concat (four-palettes backdrop background "background")
                 (four-palettes backdrop sprites "sprite")))))
