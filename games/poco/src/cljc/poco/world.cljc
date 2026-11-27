(ns poco.world
  "Where The Legend of PoCo happens: its rooms, drawn as text.

  A room is one screen: 15 rows of 16 characters, a character for each
  16x16 block of the picture. What the characters mean is `legend`:

    .  grass         f  flowers       =  path
    T  tree (solid)  ~  water (solid) #  brick wall (solid)
    d  door          _  wooden floor

  A door takes you inside (or back out): `:door` says where to, and where
  you appear there. Walk into any `d` block.

  Walk off the edge of a room, through a gap in its border, and you're in
  the room that's on that side: `:exits` says which. (The gaps and the
  exits have to match up: leave a gap with no exit, and you just can't walk
  out of it.) Rooms are numbered in the order they're listed: that's all
  the game knows about them.

  Add a room, or change one, then `(play! (poco.core/build))`.

                 north-east
        river ->  woods
          ^         ^
        home  ->  park

  (This is a tiny map to start with. Bigger is just more rooms.)"
  (:require [cartridj.screen :as screen]
            [cartridj.tileset :as tileset]
            [poco.art :as art]))

(def legend
  {\. :grass, \f :flowers, \T :tree, \~ :water, \= :path, \# :brick,
   \d :door, \_ :floor})

(def rooms
  "[[name {:rows (15 of 16 characters) :exits {:up :down :left :right room-name}}] ...]"
  [[:home
    {:exits {:up :river, :right :park}
     :door {:to :house, :x 120, :y 176, :facing :up}
     :rows ["TTTTTTT..TTTTTTT"
            "T......==......T"
            "T..f...==..T...T"
            "T......==......T"
            "T..####==..f...T"
            "T..#dd#==......T" ; the house's door
            "T......========="  ; the gap on the right
            "T......========="
            "T...f..========="
            "T......==......T"
            "T.T....==...f..T"
            "T......==......T"
            "T..f...==...T..T"
            "T......==......T"
            "TTTTTTTTTTTTTTTT"]}]

   [:house
    {:door {:to :home, :x 72, :y 76, :facing :down}
     :rows ["################"
            "#______________#"
            "#_##___________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#______________#"
            "#######dd#######"]}]

   [:park
    {:exits {:left :home, :up :woods}
     :rows ["TTTTTTT..TTTTTTT"
            "T......==......T"
            "T.f....==....f.T"
            "T......==......T"
            "T..~~..==......T"
            "T.~~~~.==..f...T"
            "=========......T" ; the gap on the left
            "=========..f...T"
            "=========......T"
            "T.~~~~.==......T"
            "T..~~..==..T...T"
            "T......==......T"
            "T.f....==....f.T"
            "T......==......T"
            "TTTTTTTTTTTTTTTT"]}]

   [:river
    {:exits {:down :home, :right :woods}
     :rows ["TTTTTTTTTTTTTTTT"
            "T..~~~~~~~~~~~~T"
            "T..~~~~~~~~~~~~T"
            "T.f............T"
            "T..............T"
            "T..~~~~~~~~~~~~T"
            "T..~~~~~~~~~~~~~" ; the gap on the right
            "T..............."
            "T..............."
            "T..~~~~~~~~~~~~T"
            "T..~~~~~~~~~~~~T"
            "T......f.......T"
            "T..............T"
            "T......==......T"
            "TTTTTTT..TTTTTTT"]}]

   [:woods
    {:exits {:left :river, :down :park}
     :rows ["TTTTTTTTTTTTTTTT"
            "TT.TT..TT.TT..TT"
            "T..T....T..T...T"
            "T.....T......T.T"
            "TT..T....T.....T"
            "T......T...T...T"
            "...T.....T.....T" ; the gap on the left
            "......T....T..TT"
            "...T.......T...T"
            "T....T.....T..TT"
            "TT.....T.T.....T"
            "T..T....T......T"
            "T......T..T...TT"
            "TT.T.TT..T.T..TT"
            "TTTTTTT..TTTTTTT"]}]])

(def room-names (mapv first rooms))

(defn room-number
  "The number the game keeps for the room called `name`."
  [name]
  (or (first (keep-indexed (fn [i n] (when (= n name) i)) room-names))
      (throw (ex-info (str "There's no room called " (pr-str name) ". The rooms: " room-names) {}))))

(def start
  "The room the game starts in, and where the player stands."
  {:room :home :x 24 :y 96})

(def screens
  "Each room as a screen for cartridj.runtime, named for the room: {:home
  screen ...}. Plus the title."
  (merge
   (into {} (for [[name {:keys [rows]}] rooms]
              [name (screen/screen {:metatiles art/metatiles :legend legend :rows rows
                                    :font-base (tileset/tile art/tiles :font)})]))
   {:title (screen/screen
            {:metatiles {:sky {:tiles [0 0 0 0] :palette 3}}
             :legend {\. :sky}
             :rows (vec (repeat 15 "................"))
             :font-base (tileset/tile art/tiles :font)
             :text [[7 8 "THE LEGEND OF"]
                    [13 11 "POCO"]
                    [10 18 "PRESS START"]]})}))
