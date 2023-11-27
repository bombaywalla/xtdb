(ns ^{:clojure.tools.namespace.repl/load false, :clojure.tools.namespace.repl/unload false}
    xtdb.protocols)

(defprotocol PNode
  (^java.util.concurrent.CompletableFuture open-query& [node query opts])
  (latest-submitted-tx [node]))

(defprotocol PSubmitNode
  (^java.util.concurrent.CompletableFuture #_<TransactionKey> submit-tx&
   [node tx-ops]
   [node tx-ops opts]))

(defprotocol PStatus
  (status [node]))

(defn max-tx [l r]
  (if (or (nil? l)
          (and r (neg? (compare l r))))
    r
    l))

(defn after-latest-submitted-tx [basis node]
  (cond-> basis
    (not (or (contains? basis :tx)
             (contains? basis :after-tx)))
    (assoc :after-tx (latest-submitted-tx node))))

(def http-routes
  [["/status" {:name :status
               :summary "Status"
               :description "Get status information from the node"}]

   ["/tx" {:name :tx
           :summary "Transaction"
           :description "Submits a transaction to the cluster"}]

   ["/query" {:name :query
              :summary "Query"}]

   ["/openapi.yaml" {:name :openapi
                     :no-doc true}]])