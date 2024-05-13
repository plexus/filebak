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
   [io.pedestal.log :as log]
   [charred.api :as json]))

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
    (< n (* 60 60))
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

(defn file-table-row [{:keys [filename content-type uuid size location upload-time] :as file}]
  [:tr.file-table-row
   [:td
    [:a {:href (str "/file/" uuid "/download")
         :title content-type} filename]
    [:div.buttons
     [:button {:hx-get (str "/file/" uuid "/edit")
               :hx-target "closest tr"
               :hx-swap "outerHTML"} "Rename"]
     [:button {:hx-delete (str "/file/" uuid)
               :hx-target "closest tr"} "Delete"]]]
   [:td (file-size-str size)]
   [:td (time-str (expiration-time file))]])

(defn file-edit-row [{:keys [filename content-type uuid size location upload-time] :as file}]
  [:tr.file-table-row
   [:td
    [:form {:hx-put (str "/file/" uuid)
            :hx-target "closest tr"
            :hx-swap "outerHTML"}
     [:input {:name "filename" :value filename :placeholder filename}]
     [:div.buttons
      [:button "Save"]]]]
   [:td (file-size-str size)]
   [:td (time-str (expiration-time file))]])

(defn index-html [{:keys [flash]}]
  [:html {:hx-headers (json/write-json-str {:X-CSRF-Token anti-forgery/*anti-forgery-token*})}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:link {:rel "stylesheet" :type "text/css" :href "styles.css"}]
    [:script {:src "htmx-1.9.12.js"}]
    [:title "Filebak"]]
   [:body
    [:h1 "Filebak"]
    (when flash
      [:div#flash flash])
    [:form.upload
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
      (for [f @files]
        [file-table-row f])]]]])

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
    (cond
      (= 0 size)
      (do
        (.delete tempfile)
        (redirect-home "Empty file, nothing uploaded"))

      (< (setting :max-file-size) size)
      (do
        (.delete tempfile)
        (redirect-home (str "File size too large, max size " (file-size-str (setting :max-file-size)))))
      :else
      (do
        (handle-upload! (:file params))
        (redirect-home (str "File " filename " uploaded"))))))

(defn find-file [uuid]
  (some #(when (= (:uuid %) uuid)
           %)
        @files))

(defn download-file [{:keys [path-params] :as req}]
  (when-let [file (find-file (:uuid path-params))]
    {:status 200
     :headers
     {"content-type" (:content-type file)
      "content-disposition" (str "attachment; filename=" (pr-str (:filename file)))}
     :body (io/file (:location file))}))

(defn delete-file [{:keys [path-params] :as req}]
  (let [uuid (:uuid path-params)]
    (if-let [file (find-file uuid)]
      (do
        (.delete (io/file (:location file)))
        (swap! files (partial remove (comp #{uuid} :uuid)))
        (ok [:td {:colspan 4} "Deleted " (:filename file)]))
      (ok [:td {:colspan 4} "Not found"]))))

(defn edit-form [{:keys [path-params] :as req}]
  (let [uuid (:uuid path-params)]
    (if-let [file (find-file uuid)]
      (ok [file-edit-row file])
      (ok [:td {:colspan 4} "Not found"]))))

(defn update-file [{:keys [path-params form-params] :as req}]
  (let [uuid (:uuid path-params)]
    (if-let [file (find-file uuid)]
      (do
        (swap! files (partial map (fn [f]
                                    (if (= uuid (:uuid f))
                                      (assoc f :filename (get form-params "filename"))
                                      f))))
        (ok [file-table-row (find-file uuid)]))
      (ok [:td {:colspan 4} "Not found"]))))

(defn routes []
  [["/" {:get {:handler #'index}}]
   ["/upload" {:post {:handler #'upload}}]
   ["/file/:uuid"
    ["" {:put {:handler #'update-file}
         :delete {:handler #'delete-file}}]
    ["/edit" {:get {:handler #'edit-form}}]
    ["/download" {:get {:handler #'download-file}}]]])

(def middleware [[ring-defaults/wrap-defaults ring-defaults/site-defaults]])

(defn app []
  (ring/ring-handler
   (ring/router
    (routes)
    {:data {}})
   (fn [req]
     {:status 404
      :headers {"content-type" "text/html"}
      :body (hiccup/render [:h1 "404 Not Found"])})
   {:middleware (cond-> middleware
                  (or (setting :basic-auth-username)
                      (setting :basic-auth-password))
                  (conj [basic-auth/wrap-basic-authentication
                         (fn [u p]
                           (= u (str (setting :basic-auth-username)))
                           (= p (str (setting :basic-auth-password))))]))}))

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
  (pprint/pprint (into {} (System/getenv)))
  (pprint/pprint opts)
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
  ["--port <port>" {:doc "HTTP port"}
   "--max-file-size <bytes>" {:doc "Maximum allowed file size in bytes"}
   "--upload-dir <path>" {:doc "Directory where to place uploaded files"}
   "--expiration-time <sec>" {:doc "Time after which uploaded files are removed, in seconds"}
   "--basic-auth-username <user>" {:doc "Username for HTTP basic auth"}
   "--basic-auth-password <password>" {:doc "Password for HTTP basic auth"}])

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
   args))

(comment
  (swap! settings merge (settings-from-env))
  (start-server! (settings-from-env))
  (.stop jetty)
  (clojure.java.browse/browse-url (str "http://localhost:" (setting :port)))
  )
