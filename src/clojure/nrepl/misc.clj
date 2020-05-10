(ns nrepl.misc
  "Misc utilities used in nREPL's implementation (potentially also
  useful for anyone extending it)."
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [requiring-resolve]))

(defn log
  [ex & msgs]
  (let [ex (when (instance? Throwable ex) ex)
        msgs (if ex msgs (cons ex msgs))]
    (binding [*out* *err*]
      (apply println "ERROR:" msgs)
      (when ex (.printStackTrace ^Throwable ex)))))

(defmacro returning
  "Executes `body`, returning `x`."
  [x & body]
  `(let [x# ~x] ~@body x#))

(defn uuid
  "Returns a new UUID string."
  []
  (str (java.util.UUID/randomUUID)))

(defn response-for
  "Returns a map containing the :session and :id from the \"request\" `msg`
   as well as all entries specified in `response-data`, which can be one
   or more maps (which will be merged), *or* key-value pairs.

   (response-for msg :status :done :value \"5\")
   (response-for msg {:status :interrupted})

   The :session value in `msg` may be any Clojure reference type (to accommodate
   likely implementations of sessions) that has an :id slot in its metadata,
   or a string."
  [{:keys [session id]} & response-data]
  {:pre [(seq response-data)]}
  (let [{:keys [status] :as response} (if (map? (first response-data))
                                        (reduce merge response-data)
                                        (apply hash-map response-data))
        response (if (not status)
                   response
                   (assoc response :status (if (coll? status)
                                             status
                                             #{status})))
        basis (merge (when id {:id id})
                     ;; AReference should make this suitable for any session implementation?
                     (when session {:session (if (instance? clojure.lang.AReference session)
                                               (-> session meta :id)
                                               session)}))]
    (merge basis response)))

(defn requiring-resolve
  "Resolves namespace-qualified sym per 'resolve'. If initial resolve fails,
  attempts to require sym's namespace and retries. Returns nil if sym could not
  be resolved."
  [sym]
  (or (resolve sym)
      (try
        (require (symbol (namespace sym)))
        (resolve sym)
        (catch Exception _))))

(defmacro with-session-classloader
  "If the session has a classloader set, then execute the body using that.
  `clojure.lang.Compiler/LOADER also need to be binded to this.

  This is typically used to trigger the sideloader, when set."
  [session & body]
  `(let [ctxcl#  (.getContextClassLoader (Thread/currentThread))
         alt-cl# (when-let [classloader# (:classloader (meta ~session))]
                   (classloader#))
         cl#     (or alt-cl# ctxcl#)]
     (.setContextClassLoader (Thread/currentThread) cl#)
     (try
       (with-bindings {clojure.lang.Compiler/LOADER cl#}
         ~@body)
       (finally
         (.setContextClassLoader (Thread/currentThread) ctxcl#)))))
