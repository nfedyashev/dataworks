(ns dataworks.core
  (:require
   [clojure.edn :as edn]
   [dataworks.authentication :as auth]
   [dataworks.resource :refer [creation-resource
                               update-resource
                               user-resource]]
   [dataworks.db.app-db :refer [query entity get-stored-functions]]
   [dataworks.collector :refer [add-collector!]]
   [dataworks.transactor :refer [add-transactor!]]
   [dataworks.transformer :refer [add-transformer!]]
   [dataworks.stream :refer [start-stream! wire-streams!]]
   [mount.core :refer [defstate] :as mount]
   [yada.yada :refer [listener as-resource]])
  (:gen-class))

(def routes
  ["/"
   [["app/"
     {"collector" (creation-resource :collector)
      "collector/" (update-resource :collector)
      "transactor" (creation-resource :transactor)
      "transactor/" (update-resource :transactor)
      "transformer" (creation-resource :transformer)
      "transformer/" (update-resource :transformer)
      "register" auth/register
      "login" auth/login
      "admin/user-roles/" auth/admin-user-roles}]
    ["user/" user-resource]
    [true (as-resource nil)]]])

(def port
  (-> "config.edn"
      slurp
      edn/read-string
      :port))

(defstate svr
  :start
  (listener routes {:port port})
  :stop
  ((:close svr)))

(defn start-stored-function!
  [f]
  (case (:stored-function/type f)
    :collector (add-collector! f)
    :transformer (add-transformer! f)
    :transactor (add-transactor! f)
    :stream (start-stream! f)))

(def start-function-xform
  (comp
   (map (juxt (comp :status start-stored-function!)
              :crux.db/id))
   (filter #(= :success (first %))) ;; TODO add error-logging
   (map second)))

(defn start-functions!
  []
  (let [f (into #{}
                start-function-xform
                (get-stored-functions))]
    (wire-streams!)
    f))

(defstate stored-fns
  :start
  (start-functions!))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (do
    (mount/start)
    (print "Herro")
    (Thread/sleep 100) ;; remove these in prod.
    (print " .")
    (Thread/sleep 100)
    (print " .")
    (Thread/sleep 100)
    (print " . ")
    (Thread/sleep 100)
    (println "ZA WARRUDO!!!!!")))
