(ns dataworks.internal
  (:require
   [cheshire.core :as cheshire]
   [clojure.core.async :refer [go go-loop chan close! alt! timeout] :as async]
   [clojure.pprint :refer [pprint] :as p]
   [dataworks.authentication :as auth]
   [dataworks.db.app-db :refer [app-db]]
   [dataworks.db.user-db :refer [user-db]]
   [dataworks.internals :refer [internal-ns]]
   [dataworks.transactor :refer [transact!]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.conversion :refer [to-object-id]]
   [monger.result :as result]
   [monger.json]
   [mount.core :refer [defstate] :as mount]
   [tick.alpha.api :as time]
   [yada.yada :refer [as-resource] :as yada]))

;; An internal does a thing, waits a period of time, then does it again.
;; An internal is a function, though inherently not a pure one.
;; An internal is a recursive function, which is called upon its return value.
;; You do not need to tell the function to recur, it is wrapped in a loop
;; at runtime.

;; An initial value may be specified to start the internal.

;; If an internal returns a map, with the key :next-run and a value of a tick
;; duration or java instant then the internal will be run after that duration
;; or at that point in time, other wise, it does so according to the :next-run
;; specified in the creation of the internal, which can be a duration or an
;; instant.

;; Example:
;;
;; POST to app/internal
;;
;; {
;;  "name" : "hourly-text",
;;  "func" : "(fn [last]
;;              (do
;;                (transact! :text
;;                  (t/format (t/formatter \"yyyy-MM-dd hh:mm:ss\")
;;                            (t/date-time (t/now))))
;;                (println (str \"Last run: \" (t/format (t/formatter \"yyyy-MM-dd hh:mm:ss\")
;;                                                       (t/date-time last))))
;;                (println (str \"Just run: \" (t/format (t/formatter \"yyyy-MM-dd hh:mm:ss\")
;;                                                       (t/date-time (t/now)))))
;;                (t/now)))",                 ;; Return value & the argument for the next loop.
;;  "next-run" : "(t/new-duration 1 :hours)", ;; Don't include these comments.
;;  "init" : "(t/now)"                        ;; Initial value for function.
;; }


(def internal-map
  (atom {}))

(defn evalidate [f]
  (binding [*ns* internal-ns]
    (eval (read-string f))))

(defn get-millis [t]
  (if (pos-int? t)
    t
    (if (string? t)
      (get-millis (evalidate t))
      (if (pos-int? t)
          t
          (if (= java.time.Duration (type t))
            (time/millis t)
            (if (= java.time.Instant (type t))
              (time/millis (time/between t (time/now))))))))) ;; TODO add proper error handling

(defn get-internals []
  (do (println "Getting Internals!")
      (let [trs (mc/find-maps app-db "internals")]
        trs)))

(defn get-internal [id]
  (mc/find-one-as-map app-db "internals" {:name (keyword id)}))

(defn new-internal [func millis init]
  {:channel (chan)
   :func (fn [init channel]
           (go-loop [value init]
             (alt! (if-let [next (:next-run value)]
                           (timeout (get-millis next))
                           (timeout millis))
                   ([] (recur (func value)))
                   channel :closed)))

   :init init})

(defn add-internal! [{:keys [name func next-run init] :as params}]
  (do
    (if-let [{:keys [channel]} ((keyword name) @internal-map)]
      (close! channel))
    (swap! internal-map
           (fn [i-map]
             (assoc i-map
                    (keyword name)
                    (new-internal (evalidate func)
                                  (get-millis next-run)
                                  (evalidate init)))))
    (let [{:keys [channel func init]} ((keyword name) @internal-map)]
      (func init channel))))

(defn create-internal! [{:keys [name func next-run init]}]
  (if-let [ins (mc/insert-and-return app-db "internals"
                                     {:name (keyword name)
                                      :func func
                                      :next-run next-run
                                      :init init})]
      (add-internal! ins)))

(defn update-internal! [id params]
  (let [update (mc/update app-db "internals"
                          {:name (keyword id)}
                          params)]
    (if (result/acknowledged? update)
      (do
        (add-internal! params)
          "success")
      "failure")))

(defn start-internals! []
  (do
    (println "Starting Internals!")
    (let [nts (get-internals)
          started-nts (map add-internal! nts)]
      (if (= (count nts)
             (count started-nts))
        (println "Internals Started!")
        (println "Internals Failed to Start.")))))

(defstate internal-state
  :start
  (start-internals!)
  :stop
  (do
    (map (fn [{:keys [channel]}] (close! channel)) @internal-map)
    (reset! internal-map {})))

(def internals
  (yada/resource
   {:id :internals
    :description "this is the resource that returns all internal documents"
    :authentication auth/dev-authentication
    :authorization auth/dev-authorization
    :methods {:get
              {:produces "application/json"
               :response (fn [ctx] (get-internals))}
              :post
              {:consumes #{"application/json"}
               :response
               (fn [ctx]
                 (let [body (:body ctx)]
                   (create-internal! body)))}}}))

(def internal
  (yada/resource
   {:id :internal
    :description "resource for individual internal"
    :parameters {:path {:id String}} ;; do I need plurumatic's schema thing?
    :authentication auth/dev-authentication
    :authorization auth/dev-authorization
    :methods {:get
              {:produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])]
                   (get-internal id)))}
              :post
              {:consumes #{"application/json"}
               :produces "application/json"
               :response
               (fn [ctx]
                 (let [id (get-in ctx [:request :route-params :id])
                       body (:body ctx)]
                   {:update-status  ;; TODO make this less shitty
                    (update-internal! id body)}))}}}))