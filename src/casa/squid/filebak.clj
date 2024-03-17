(ns casa.squid.filebak
  (:gen-class)
  (:require
   [camel-snake-kebab.core :as csk]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [lambdaisland.cli :as cli]
   [lambdaisland.dotenv :as dotenv]
   [lambdaisland.hiccup :as hiccup]
   [overtone.at-at :as at]
   [reitit.ring :as ring]
   [ring.adapter.jetty :as jetty]
   [ring.middleware.anti-forgery :as anti-forgery]
   [ring.middleware.basic-authentication :as basic-auth]
   [ring.middleware.defaults :as ring-defaults]
   [io.pedestal.log :as log]))

(defonce settings (atom {:port 8080
                         :max-file-size (* 10 1024 1024)
                         :upload-dir "."
                         :expiration-time (* 30 60)}))

(defonce files (atom []))

(defonce at-pool (at/mk-pool))

(defn setting [k]
  (get @settings k))

(defn metadata-file []
  (io/file (setting :upload-dir) "files.edn"))

(add-watch
 files ::persist
 (fn [k r o n]
   (spit
    (metadata-file)
    (with-out-str (pprint/pprint n)))))

(defn file-size-str [n]
  (cond
    (< n 1024)
    (str n " bytes")
    (< n (* 1024 1024))
    (format "%.1fKiB" (double (/ n 1024)))
    :else
    (format "%.1fMiB" (double (/ n 1024 1024)))))

(defn time-str [n]
  (cond
    (< n 100)
    (str n " seconds")
    (= 1 (Math/round (double (/ n 60))))
    (str "1 minute")
    (< n (* 58 60))
    (str (Math/round (double (/ n 60))) " minutes")
    (= 1 (long (Math/floor (double (/ n 60 60)))))
    (str "1 hour"
         (when (< 10 (Math/round (double (/ (mod n 3600) 60))))
           (str " " (time-str (mod n 3600)))))
    :else
    (str (long (Math/floor (double (/ n 60 60)))) " hours"
         (when (< 10 (Math/round (double (/ (mod n 3600) 60))))
           (str " " (time-str (mod n 3600)))))))

(defn epoch-now []
  (long (/ (System/currentTimeMillis) 1e3)))

(defn expiration-time [{:keys [upload-time]}]
  (- (Integer. (setting :expiration-time)) (- (epoch-now) upload-time)))

(defn expired? [file]
  (<= (expiration-time file) 0))

(defn index-html [{:keys [flash]}]
  [:html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:link {:rel "stylesheet" :type "text/css" :href "styles.css"}]
    [:title "Filebak"]]
   [:body
    [:h1 "Filebak"]
    (when flash
      [:div#flash flash])
    [:form
     {:action "/upload" :method "post" :enctype "multipart/form-data"}
     [:input#file {:name "file" :type "file"}]
     [:input {:name "__anti-forgery-token" :type "hidden" :value anti-forgery/*anti-forgery-token*}]
     [:button "Upload"]]
    [:table
     [:thead
      [:tr
       [:th "Filename"] [:th "Size"] [:th "Expires in"]]]
     [:tbody
      (when (not (seq @files))
        [:tr
         [:td#empty-message {:colspan 3}
          "No files available for download"]])
      (for [{:keys [filename content-type uuid size location upload-time] :as file} @files]
        [:tr
         [:td [:a {:href (str "/download/" uuid)}filename]]
         [:td (file-size-str size)]
         [:td (time-str (expiration-time file))]])]]]]
  )

(defn ok [body]
  {:status 200
   :headers {"content-type" "text/html"}
   :body (hiccup/render body)})

(defn redirect-home
  ([]
   {:status 303
    :headers {"Location" "/"}})
  ([message]
   {:status 303
    :headers {"Location" "/"}
    :flash message}))

(defn index [req]
  (ok (index-html {:flash (:flash req)})))

(defn handle-upload! [{:keys [filename content-type tempfile size]}]
  (let [uuid (str (random-uuid))
        target (io/file (setting :upload-dir) uuid)]
    (.renameTo tempfile target)
    (swap! files conj {:filename filename
                       :content-type content-type
                       :size size
                       :uuid uuid
                       :location (.getCanonicalPath target)
                       :upload-time (epoch-now)})))

(defn upload [{:keys [params] :as req}]
  (let [{:keys [filename tempfile size]} (:file params)]
    (if (< (setting :max-file-size) size)
      (do
        (.delete tempfile)
        (redirect-home (str "File size too large, max size " (file-size-str (setting :max-file-size)))))
      (do
        (handle-upload! (:file params))
        (redirect-home (str "File " filename " uploaded"))))))

(defn download [{:keys [path-params] :as req}]
  (when-let [file (some #(when (= (:uuid %) (:uuid path-params))
                           %)
                        @files)]
    {:status 200
     :headers
     {"content-type" (:content-type file)
      "content-disposition" (str "attachment; filename=" (pr-str (:filename file)))}
     :body (io/file (:location file))}))

(defn routes []
  [["/" {:get {:handler #'index}}]
   ["/upload" {:post {:handler #'upload}}]
   ["/download/:uuid" {:get {:handler #'download}}]
   ["/styles.css" {:get (fn [_] {:status 200 :content-type "text/css" :body (io/resource "public/styles.css")})}]])

(def middleware [[ring-defaults/wrap-defaults ring-defaults/site-defaults]])

(defn app []
  (ring/ring-handler
   (ring/router
    (routes)
    {:data {:middleware (cond-> middleware
                          (or (setting :basic-auth-username)
                              (setting :basic-auth-password))
                          (conj [basic-auth/wrap-basic-authentication
                                 (fn [u p]
                                   (= u (str (setting :basic-auth-username)))
                                   (= p (str (setting :basic-auth-password))))]))}})))

(defn settings-from-env []
  (update-keys
   (dotenv/parse-dotenv
    (try
      (slurp ".env")
      (catch java.io.FileNotFoundException e
        ""))
    {:vars (into {} (System/getenv))})
   (comp keyword str/lower-case csk/->kebab-case #(str/replace % #"^FILEBAK_" ""))))

(defn start-server! [opts]
  (swap! settings merge opts)
  (reset! files (if (.exists (metadata-file))
                  (edn/read-string (slurp (metadata-file)))
                  []))
  (log/info :filebak/starting (select-keys @settings [:port :max-file-size :expiration-time :upload-dir]))
  (def jetty
    (jetty/run-jetty
     (fn [req] ((app) req))
     {:port (Integer. (setting :port))
      :join? false})))

(defn start-clean-task! [opts]
  (at/interspaced
   1000
   (fn []
     (swap! files (fn [files]
                    (doseq [f files
                            :when (expired? f)]
                      (.delete (io/file (:location f))))
                    (remove expired? files))))
   at-pool
   {:desc "Clean up files"}))

(def flags
  ["--port" {:doc "HTTP port"
             :default (setting :port)}
   "--max-file-size" {:doc "Maximum allowed file size in bytes"
                      :default (setting :max-file-size)}
   "--upload-dir" {:doc "Directory where to place uploaded files"
                   :default (setting :upload-dir)}
   "--expiration-time" {:doc "Time after which uploaded files are removed, in seconds"
                        :default (setting :expiration-time)}
   "--basic-auth-username" {:doc "Username for HTTP basic auth"}
   "--basic-auth-password" {:doc "Password for HTTP basic auth"}])

(def doc
  "Allow files to be uploaded and downloaded, after a fixed amount of time they
are removed again. Optionally provides HTTP basic auth.")

(defn command [opts]
  (start-server! opts)
  (start-clean-task! opts)
  @(promise))

(defn -main [& args]
  (cli/dispatch
   {:name "filebak"
    :doc doc
    :command #'command
    :flags flags
    :init (settings-from-env)}
   args)
  )

(comment
  (swap! settings merge (settings-from-env))
  (start! (settings-from-env))
  (.stop jetty)
  )
