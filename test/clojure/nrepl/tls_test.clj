(ns nrepl.tls-test
  (:require [clojure.stacktrace :as st]
            [clojure.test :refer [deftest is]]
            [com.github.ivarref.locksmith :as locksmith]
            [nrepl.core :as nrepl]
            [nrepl.server :as server]
            [nrepl.tls-client-proxy :as tls-client-proxy]
            [nrepl.transport :as transport])
  (:import (clojure.lang ExceptionInfo IDeref)
           (java.lang AutoCloseable)
           (java.net SocketException)
           (javax.net.ssl SSLException SSLHandshakeException)))

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (locksmith/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

(comment
  (do
    (load-file "src/clojure/nrepl/core.clj")
    (load-file "src/clojure/nrepl/misc.clj")
    (load-file "src/clojure/nrepl/server.clj")
    (load-file "src/clojure/nrepl/tls_client_proxy.clj")))

; Make sure TLS is in fact used
(defn tls-connect
  ^AutoCloseable [opts]
  (#'nrepl/tls-connect opts))

(deftest happy-case
  (let [[server-keys client-keys] (gen-key-pair)]
    (with-open [^AutoCloseable server (server/start-server :tls? true :tls-keys-str server-keys)]
      (with-open [transport (tls-connect {:tls-keys-str client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2
                 (-> (nrepl/message client {:op   "eval"
                                            :code "(+ 1 1)"})
                     first
                     nrepl/read-response-value
                     :value))))))))

(deftest bad-config
  (is (thrown? ExceptionInfo (server/start-server :tls? true)))
  (is (= :nrepl.server/invalid-start-request
         (:nrepl/kind (try
                        (server/start-server :tls? true)
                        (catch Throwable t
                          (ex-data t)))))))

(defn noisy-deref [e]
  (let [dereffed (deref e 60000 :timeout)]
    (if (= :timeout dereffed)
      (do
        (binding [*out* *err*]
          (println "Timeout when trying to deref exception!" e))
        (is false "Timeout when deref exception!")
        nil)
      dereffed)))

(defn tls-socket-exception? [exception-promise]
  (let [e (noisy-deref exception-promise)]
    (or (instance? SocketException e)
        (instance? SSLException e))))

(deftest bad-keys
  (let [[server-keys _] (gen-key-pair)
        [_ client-keys] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [transport (tls-connect {:tls-keys-str client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn (fn [& args]
                                                          ;; there appears to be
                                                          ;; a (race?) condition
                                                          ;; that the
                                                          ;; transport-fn could
                                                          ;; also be called.
                                                          (try
                                                            (apply transport/bencode args)
                                                            (catch Exception e
                                                              (is (instance? SSLHandshakeException e)))))})]
        (let [client (nrepl/client transport 30000)]
          (try
            (-> (nrepl/message client {:op   "eval"
                                       :code "(+ 1 1)"})
                first
                nrepl/read-response-value
                :value)
            (is false "Expected an exception to be thrown.")
            (catch Exception _e))
          (is (tls-socket-exception? exception)))))))

(deftest server-keys-then-good
  (let [[server-keys good-client-keys] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [transport (tls-connect {:tls-keys-str server-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn (fn [& args]
                                                          ;; there appears to be
                                                          ;; a (race?) condition
                                                          ;; that the
                                                          ;; transport-fn could
                                                          ;; also be called.
                                                          (try
                                                            (apply transport/bencode args)
                                                            (catch Exception e
                                                              (is (instance? SSLHandshakeException e)))))})]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception
                       (-> (nrepl/message client {:op   "eval"
                                                  :code "(+ 1 1)"})
                           first
                           nrepl/read-response-value
                           :value)))
          (is (tls-socket-exception? exception))))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2
                 (-> (nrepl/message client {:op   "eval"
                                            :code "(+ 1 1)"})
                     first
                     nrepl/read-response-value
                     :value))))))))

(deftest bad-keys-then-good
  (let [[server-keys good-client-keys] (gen-key-pair)
        [_ bad-client-keys] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [transport (tls-connect {:tls-keys-str bad-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn (fn [& args]
                                                          ;; there appears to be
                                                          ;; a (race?) condition
                                                          ;; that the
                                                          ;; transport-fn could
                                                          ;; also be called.
                                                          (try
                                                            (apply transport/bencode args)
                                                            (catch Exception e
                                                              (is (instance? SSLHandshakeException e)))))})]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception
                       (-> (nrepl/message client {:op   "eval"
                                                  :code "(+ 1 1)"})
                           first
                           nrepl/read-response-value
                           :value)))
          (is (tls-socket-exception? exception))))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2
                 (-> (nrepl/message client {:op   "eval"
                                            :code "(+ 1 1)"})
                     first
                     nrepl/read-response-value
                     :value))))))))

(deftest bad-keys-then-good-no-consume-exception
  (let [[server-keys good-client-keys] (gen-key-pair)
        [_ bad-client-keys] (gen-key-pair)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys)]
      (with-open [transport (tls-connect {:tls-keys-str bad-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn (fn [& args]
                                                          ;; there appears to be
                                                          ;; a (race?) condition
                                                          ;; that the
                                                          ;; transport-fn could
                                                          ;; also be called.
                                                          (try
                                                            (apply transport/bencode args)
                                                            (catch Exception e
                                                              (is (instance? SSLHandshakeException e)))))})]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception
                       (-> (nrepl/message client {:op   "eval"
                                                  :code "(+ 1 1)"})
                           first
                           nrepl/read-response-value
                           :value)))))
      (with-open [transport (tls-connect {:tls-keys-str good-client-keys
                                          :host         "127.0.0.1"
                                          :port         (:port server)
                                          :transport-fn transport/bencode})]
        (let [client (nrepl/client transport 30000)]
          (is (= 2
                 (-> (nrepl/message client {:op   "eval"
                                            :code "(+ 1 1)"})
                     first
                     nrepl/read-response-value
                     :value))))))))

(deftest regular-connection-times-out
  (let [[server-keys _] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [^AutoCloseable _ (nrepl/connect :port (:port server))]
        (is (tls-socket-exception? exception))))))

(deftest regular-connection+eval-fails
  (let [[server-keys _] (gen-key-pair)
        exception (promise)]
    (with-open [server (server/start-server :tls? true
                                            :tls-keys-str server-keys
                                            :consume-exception (partial deliver exception))]
      (with-open [^AutoCloseable transport (nrepl/connect :port (:port server))]
        (let [client (nrepl/client transport 30000)]
          (is (thrown? Exception
                       (-> (nrepl/message client {:op   "eval"
                                                  :code "(+ 1 1)"})
                           first
                           nrepl/read-response-value
                           :value)))
          (is (tls-socket-exception? exception)))))))

(deftest tls-client-proxy-test
  (let [[server-keys client-keys] (gen-key-pair)]
    (with-redefs [tls-client-proxy/atomic-println (fn [& _] nil)]
      (with-open [server (server/start-server :tls? true :tls-keys-str server-keys)]
        (let [state (tls-client-proxy/start-tls-proxy {:remote-host  "127.0.0.1"
                                                       :remote-port  (:port server)
                                                       :tls-keys-str client-keys
                                                       :port-file    nil
                                                       :block?       false})]
          (try
            (let [proxy-port (deref (:port-promise @state) 3000 nil)]
              (is (> proxy-port 0))
              (when (> proxy-port 0)
                (with-open [^AutoCloseable transport (nrepl/connect :port proxy-port)]
                  (let [client (nrepl/client transport 30000)]
                    (is (= 2 (-> (nrepl/message client {:op   "eval"
                                                        :code "(+ 1 1)"})
                                 first
                                 nrepl/read-response-value
                                 :value)))))))
            (finally
              (tls-client-proxy/stop! state))))))))
