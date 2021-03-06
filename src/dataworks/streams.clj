(ns dataworks.streams
  (:require
   [dataworks.db.user-db :refer [user-db
                                 submit-tx
                                 query
                                 entity]]
   [dataworks.utils.common :refer :all]
   [dataworks.utils.time :refer :all]
   [cheshire.core :as cheshire]
   [clojure.core.async :refer [go >!]]
   [crux.api :as crux]
   [tick.alpha.api :as tick]))

(def stream-ns *ns*)

(def nodes
  (atom {}))

(def edges
  (atom []))

(defn stream!
  [stream data]
  (if-let [channel (get-in @nodes [stream :input])]
    (go (>! channel data))))

(require '[dataworks.transactors :refer [transact!]])
(require '[dataworks.transformers :refer [transformers]])

;; This is where the actual streams live.
;; They only live here at runtime.
;; DO NOT PUT CODE IN THIS NAMESPACE.
;; (... beyond what's above)
