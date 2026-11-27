(ns poco.util
  #?(:clj (:require [clojure.java.io :as io]
                    [clojure.edn :as edn]))
  #?(:cljs (:require-macros [poco.util :refer [inline-edn-resource]])))

#?(:clj
   (defmacro inline-edn-resource [path]
     (if-let [res (io/resource path)]
       (edn/read-string (slurp res))
       (throw (ex-info (str "Resource not found: " path) {:path path})))))