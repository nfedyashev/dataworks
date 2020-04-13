(ns dataworks.transformer
  (:require
   [dataworks.common :refer :all]
   [dataworks.transformers :refer [transformer-ns]]))

(def transformer-map
  (atom {}))

;; TODO Add validation here.
(defn evals? [{:transformer/keys [name function] :as params}]
  (println "evalidating" name)
  (binding [*ns* transformer-ns]
    (try (eval function)
         (catch Exception e
           {:status :failure
            :message :unable-to-evalidate-function
            :details (.getMessage e)}))))

(defn evalidate [params]
  (if-vector-conj params
    "params"
    (->? params
         evals?
         function?)))

(defn add-transformer!
  ([{:transformer/keys [name function] :as params}]
   (if-let [f (evalidate params)]
     (apply add-transformer! f)))
  ([{:transformer/keys [name] :as params} f]
   (swap! transformer-map #(assoc % (keyword name) f))
   {:status :success
    :message :transformer-added
    :details params}))

(defn apply-transformer! [params]
  (apply add-transformer! params))

(defn db-fy
  [params]
  (if-vector-first params
    db-fy
    {:crux.db/id (keyword "transformer" (:name params))
     :transformer/name (keyword (:name params))
     :transformer/function (:function params)
     :stored-function/type :transformer}))

(defn create-transformer! [transformer]
  (->? transformer
       (blank-field? :name :function)
       valid-name?
       (parseable? :function)
       (function-already-exists? :transformer)
       db-fy
       evalidate
       added-to-db?
       apply-transformer!))

(defn update-transformer! [path-name transformer]
  (->? transformer
       (updating-correct-function? path-name)
       (blank-field? :function)
       (parseable? :function)
       (add-current-stored-function :transformer)
       (has-params? :transformer :name)
       (valid-update? :transformer :function)
       db-fy
       evalidate
       added-to-db?
       apply-transformer!))

(defn start-transformers! []
  (do (println "Starting Transformers!")
      (let [trs (get-stored-functions :transformer)
            status (map add-transformer! trs)]
        (if (every? #(= (:status %) :success) status)
          (println "Transformers Started!")
          (println "Transformers Failed to Start:"
                   (map :name status))))))

(defstate transformer-state
  :start (start-transformers!)
  :stop (reset! transformer-map {}))

(defn def-ify [xformer]
  `(def ~xformer
     ~(get transformer-map (keyword xformer))))

(defmacro transformers
  [xformers & forms]
  (apply ->let
         (conj forms
               (map def-ify
                    xformers))))