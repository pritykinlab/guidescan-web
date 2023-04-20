(ns guidescan-web.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]))

(defrecord Configuration [config-file config]
  component/Lifecycle
  (start [this]
    (when (nil? config)
      (timbre/info "Loading configuration")
      (if-let [c (edn/read-string (slurp config-file))]
        (assoc this :config c))))
  (stop [this]
    (assoc this :config nil)))

(defn create-config [config-file]
  (map->Configuration {:config-file config-file}))

(defn get-grna-db-path [config organism enzyme]
  (.getPath
   (io/file
    (:grna-database-path-prefix (:config config))
    (get (:grna-database-path-map (:config config))
         {:organism organism
          :enzyme enzyme}))))

(defn contains-grna-db-path?
  "Returns true if the organism and enzyme have a path
  in the configuration file."
  [config organism enzyme]
  (and (contains? (:config config) :grna-database-path-map)
       (contains? (:grna-database-path-map (:config config))
                  {:organism organism
                   :enzyme enzyme})))

(defn get-grna-db-pos-offset
  "Returns the offset that must be added to the observed
  position of an alignment to get the true position."
  [config organism enzyme]
      (get (:grna-database-offset-map  (:config config))
           {:organism organism
            :enzyme enzyme} 0))