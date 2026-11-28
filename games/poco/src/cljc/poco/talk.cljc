(ns poco.talk
  "What the game says, in a box over the room: two lines of words, at most
  28 letters a line (capitals, digits, and ! . : - ? only).

  There's a message for finding each item (so picking one up says what it
  is), for each room with a :sign in poco.world (read it by facing it and
  pressing A), and for each of a room's :npcs (talk by facing them, and A). The messages are numbered in the order they're listed
  here, and the game keeps that number in `message`; the words themselves
  are a table in the ROM, 64 bytes each: two rows of the box, of 32 tiles
  (a blank at each side of the 28 letters)."
  (:require [cartridj.font :as font]
            [cartridj.tileset :as tileset]
            [poco.art :as art]
            [poco.items :as items]
            [poco.world :as world]))

(def width 28)

(def messages
  "[{:id [:found item], [:sign room] or [:npc room kind], :lines [first second]} ...]"
  (vec (concat
        (for [{:keys [id name doc]} items/items]
          {:id [:found id] :lines [(str "YOU GOT THE " name "!") doc]})
        (for [[room {:keys [sign]}] world/rooms :when sign]
          {:id [:sign room] :lines sign})
        (for [[room {:keys [npcs]}] world/rooms
              {:keys [kind says]} npcs]
          {:id [:npc room kind] :lines says}))))

(defn number
  "The number of the message with this `id`."
  [id]
  (or (first (keep-indexed (fn [i m] (when (= id (:id m)) i)) messages))
      (throw (ex-info (str "There's no message " (pr-str id)) {:id id}))))

(defn- row
  "One row of the box: `words` in the middle, as 32 tile numbers."
  [words]
  (when (> (count words) width)
    (throw (ex-info (str (pr-str words) " is longer than " width " letters") {})))
  (font/encode (str "  " words (apply str (repeat (- width (count words)) \space)) "  ")
               (tileset/tile art/tiles :box-font)))

(def table
  "Every message's 64 bytes."
  (vec (mapcat (fn [{[a b] :lines}] (concat (row a) (row (or b ""))))
               messages)))
