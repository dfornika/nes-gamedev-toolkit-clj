(ns poco.items
  "The things the player can carry: what they're called, what they look
  like, and what the items screen says about them.

  Each item has a 16x16 drawing (. is see-through, 1 2 3 are the colors
  of its palette), one of the items screen's four `palettes`, and two
  lines of words. Words can be capitals, digits, and ! . : - ? and at most
  28 letters a line.

  The items are numbered in the order they're listed (there's room for
  eight): that's their place on the items screen, and the bit that says
  you have it. Nothing gives the player items yet: `starting` says which
  they begin with. In the debugger, set the variable `items` to give or take
  them (bit 0 is the first).

  The drawings here are placeholders: they get replaced by real art.")

(def stick
  ["................"
   ".............123"
   "............1.3."
   "...........123.."
   "..........123..."
   ".........123...."
   "........123....."
   ".......123......"
   "......123......."
   ".....123........"
   "....123........."
   "...123.........."
   "..123..........."
   ".123............"
   "123............."
   "................"])

(def bat
  ["............1223"
   "...........1223."
   "..........1223.."
   ".........1223..."
   "........1223...."
   ".......1223....."
   ".......23......."
   "......23........"
   ".....23........."
   "....23.........."
   "...23..........."
   "..23............"
   ".23............."
   "23.............."
   "2..............."
   "................"])

(def pizza
  ["................"
   ".33333333333333."
   ".33333333333333."
   "3111111111111111"
   ".31112211111111."
   "..311111122111.."
   "..311111111111.."
   "...3111111111..."
   "....31122111...."
   "....31111111...."
   ".....111111....."
   "......1211......"
   "......1111......"
   ".......11......."
   ".......13......."
   "................"])

(def firecracker
  ["................"
   ".......1..1....."
   ".........1......"
   "........3......."
   "........3......."
   ".....333333....."
   ".....222223....."
   ".....222223....."
   ".....111111....."
   ".....111111....."
   ".....222223....."
   ".....222223....."
   ".....222223....."
   ".....222223....."
   ".....333333....."
   "................"])

(def slot
  [".22222222222222."
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   "2..............2"
   ".22222222222222."])

(def palettes
  "The four background palettes the items screen is drawn in (three colors
  each, and the backdrop). Palette 0 is the words' and frames', so its
  first color is the color of the letters."
  {:backdrop   :dark-gray
   :background [[:white :light-gray :gray]            ; 0 words
                [:light-orange :brown :dark-brown]    ; 1 wood
                [:light-yellow :red :light-orange]    ; 2 pizza
                [:white :red :dark-red]]})            ; 3 firecracker

(def items
  [{:id :stick
    :name "STICK"
    :doc "FOR POKING THINGS."
    :palette 1 :drawing stick}
   {:id :bat
    :name "BASEBALL BAT"
    :doc "SWING IT AT TEENAGERS."
    :palette 1 :drawing bat}
   {:id :pizza
    :name "PIZZA SLICE"
    :doc "RESTORES HEARTS. YUM."
    :palette 2 :drawing pizza}
   {:id :firecracker
    :name "FIRECRACKER"
    :doc "LOUD. HANDLE WITH CARE."
    :palette 3 :drawing firecracker}])

(def starting
  "The items the player starts the game with."
  #{:stick :bat :pizza :firecracker})

(def slots 8)
