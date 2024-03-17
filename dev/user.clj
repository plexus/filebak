(ns user)

(defmacro jit [sym]
  `(requiring-resolve '~sym))

(defn browse []
  ((jit clojure.java.browse/browse-url) "http://localhost:8000"))

(defn go []
  ((jit casa.squid.filebak/start-server!) ((jit casa.squid.filebak/settings-from-env)))
  ((jit casa.squid.filebak/start-clean-task!) ((jit casa.squid.filebak/settings-from-env))))
