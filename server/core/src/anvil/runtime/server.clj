(ns anvil.runtime.server
  (:use org.httpkit.server
        compojure.core
        clojure.pprint
        [slingshot.slingshot :only [throw+ try+]]
        anvil.runtime.util)
  (:require [anvil.runtime.conf :as conf]
            [anvil.runtime.browser-ws :as browser-ws]
            [anvil.runtime.secrets :as secrets]
            [digest]
            [ring.util.response :as resp]
            [ring.util.mime-type :as mime-type]
            [clojure.data.json :as json]
            [anvil.core.google-oauth2 :as oauth]
            [hiccup.util :as hiccup-util]
            [anvil.runtime.app-data :as app-data]
            [anvil.util :as utils]
            [crypto.random :as random]
            [clojure.string :as string]
            [clojure.data.codec.base64 :as b64]
            [clojure.tools.logging :as log]
            [anvil.dispatcher.serialisation.lazy-media :as lazy-media]
            [anvil.dispatcher.core :as dispatcher]
            [anvil.dispatcher.native-rpc-handlers.users.core :as user-service]
            [anvil.dispatcher.native-rpc-handlers.saml :as saml]
            [org.httpkit.client :as http]
            [anvil.dispatcher.user-lazy-media]
            [anvil.runtime.app-log :as app-log]
            [anvil.dispatcher.types :as types]
            [ring.util.codec :as codec]
            [anvil.dispatcher.native-rpc-handlers.cookies :as cookies]
            [ring.middleware.cookies]
            [buddy.sign.jwt]
            [buddy.core.keys]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [anvil.util :as util]
            [anvil.metrics :as metrics]
            [anvil.core.worker-pool :as worker-pool]
            [anvil.dispatcher.native-rpc-handlers.util :as rpc-util]
            [anvil.runtime.sessions :as sessions]
            [anvil.runtime.util :as runtime-util]
            [anvil.dispatcher.serialisation.blocking-hacks :as blocking-hacks])
  (:import (java.io ByteArrayInputStream)
           (anvil.dispatcher.types Media MediaDescriptor InputStreamMedia ChunkedStream)
           (org.apache.commons.codec.binary Base64)
           (java.net URLEncoder URLDecoder)
           (com.onelogin.saml2.authn AuthnRequest SamlResponse)
           (com.onelogin.saml2.settings SettingsBuilder Saml2Settings)
           (com.onelogin.saml2.util Constants)))

(clj-logging-config.log4j/set-logger! :level :info)
(clj-logging-config.log4j/set-logger! "com.onelogin.saml2" :level :debug)

(defn app-500
  ([req] (app-500 req "An internal error occurred"))
  ([{:keys [app-id app-origin] :as req} message]
   (log/debug "Couldn't load app" app-id ":" message)
   (-> (resp/response (-> (slurp (runtime-client-resource req "/500-app.html"))
                          (.replace "{{anvil-error}}" (hiccup-util/escape-html message))
                          (clojure.string/replace #"\{\{cdn\-origin\}\}" (runtime-util/get-static-origin req))))
       (resp/content-type "text/html")
       (resp/set-cookie "anvil-test-cookie" true)
       (resp/status 500))))

(defn app-404
  ([req] (app-404 req false))
  ([{:keys [app-id app-origin] :as req} app-exists-but-no-key?]
   (log/debug "Couldn't load app" app-id)
   (-> (resp/response (-> (slurp (if app-exists-but-no-key? (runtime-client-resource req "/404-app-no-key.html")
                                                            (runtime-client-resource req "/404-app.html")))
                          (.replace "{{canonical-url}}" (hiccup-util/escape-html app-origin))
                          (clojure.string/replace #"\{\{cdn\-origin\}\}" (runtime-util/get-static-origin req))))
       (resp/header "x-anvil-sig" (secrets/encrypt-str-with-global-key :anvil-sig-header ""))
       (resp/content-type "text/html")
       (resp/set-cookie "anvil-test-cookie" true)
       (resp/status (if app-exists-but-no-key? 403 404)))))


(defonce get-embedding-restrictions (fn [app-map]
                                      (when-not (or (:allow_embedding app-map) (nil? (:allow_embedding app-map)))
                                        "none")))


(def set-app-embedding-impl! (util/hook-setter #{get-embedding-restrictions}))

(defn get-app-from-request
  ([request] (get-app-from-request request true))
  ([request allow-errors?]
   (app-data/get-app (:app-info request)
                     (app-data/get-version-spec-for-environment (:environment request))
                     allow-errors?)))

(defn get-service-code [app-map]
  (apply str
         "var anvilServicePreloadModules=[];"
         (for [{:keys [source]} (:services app-map)
               :let [[_ service-name anvil-prefix] (re-matches #"/runtime/services/((anvil/)?[A-Za-z0-9]+).yml" source)]

               :when service-name
               :let [service-conf (yaml/parse-string (slurp (or (io/resource (str "services-core/" service-name ".yml"))
                                                                (io/resource (str "services-platform/" service-name ".yml")))) true)
                     source-paths (:path_whitelist service-conf)
                     preload-modules (if-let [p (:preload_module service-conf)] [p] (:preload_modules service-conf))]]
           (apply str
                  (concat
                    (for [pm preload-modules]
                      (str "anvilServicePreloadModules.push(" (util/write-json-str pm) ");"))
                    (for [p source-paths
                          :let [content (slurp (or (io/resource (str "services-core/" anvil-prefix p))
                                                   (io/resource (str "services-platform/" anvil-prefix p))))
                                fake-path (str "anvil-services/" anvil-prefix p)]]
                      (str "Sk.builtinFiles.files[" (util/write-json-str fake-path) "] = " (util/write-json-str content) ";")))))))

(defonce service-snippet-fns (atom {}))

(swap! service-snippet-fns assoc "/runtime/services/segment.yml"
       (fn [app-map {:keys [client_config]}]
         (format "<script type=\"text/javascript\">\n  !function(){var analytics=window.analytics=window.analytics||[];if(!analytics.initialize)if(analytics.invoked)window.console&&console.error&&console.error(\"Segment snippet included twice.\");else{analytics.invoked=!0;analytics.methods=[\"trackSubmit\",\"trackClick\",\"trackLink\",\"trackForm\",\"pageview\",\"identify\",\"reset\",\"group\",\"track\",\"ready\",\"alias\",\"debug\",\"page\",\"once\",\"off\",\"on\"];analytics.factory=function(t){return function(){var e=Array.prototype.slice.call(arguments);e.unshift(t);analytics.push(e);return analytics}};for(var t=0;t<analytics.methods.length;t++){var e=analytics.methods[t];analytics[e]=analytics.factory(e)}analytics.load=function(t){var e=document.createElement(\"script\");e.type=\"text/javascript\";e.async=!0;e.src=(\"https:\"===document.location.protocol?\"https://\":\"http://\")+\"cdn.segment.com/analytics.js/v1/\"+t+\"/analytics.min.js\";var n=document.getElementsByTagName(\"script\")[0];n.parentNode.insertBefore(e,n)};analytics.SNIPPET_VERSION=\"4.0.0\";\n  analytics.load(\"%s\");\n  analytics.page();\n  }}();\n</script>"
                 (hiccup-util/escape-html (:write_key client_config)))))

(defn- get-service-snippets [app-map]
  (->> (for [{:keys [source] :as service} (:services app-map)
             :let [get-snippet (get @service-snippet-fns source)]
             :when get-snippet]
         (get-snippet app-map service))
       (apply str)))

(defn with-anvil-cookies [resp session]
  ;; Takes the current value of the anvil cookies from the session and puts them onto an HTTP response
  (update-in resp [:cookies] #(into (or % {})
                                (for [type [:local :shared]]
                                  [(get conf/app-cookie-names type)
                                   (merge {:value     (or (when-let [cookie-serialised (get-in @session [:cookies type])] cookie-serialised) "")
                                           :path      "/"
                                           :http-only true
                                           :max-age   2147483647}
                                          (when conf/force-secure-cookies?
                                            {:same-site :none
                                             :secure    true})
                                          (when (= type :shared)
                                            {:domain (:shared-cookie-domain @session)}))]))))

(defn render-app-description [description]
  (hiccup-util/escape-html
    (util/or-str description
                 "This app is built with Anvil, the platform for building full-stack web apps quickly and robustly.")))

(defn image-from-metadata [app-origin metadata key not-found]
  (let [img-url (fn [url]
                  (if-let [[_ n] (when url (re-matches #"asset:(.*)" url))]
                    (str app-origin "/_/theme/" (util/real-actual-genuine-url-encoder n))
                    url))]
    (util/or-str (img-url (get metadata key)) (str conf/static-root-url not-found))))

(defn serve-app [{:keys [app-id environment environment-from-url app-session] :as req} client-params {:keys [action print-id print-key]}]
  (utils/timeit "Serve app" [checkpoint!]
    (try+
      (let [environment (or environment-from-url environment) ;; Ignore overrides from the session on reload
            [app-info app-map style head-html commit-id]
            (app-data/sanitised-app-and-style-for-client app-id
                                                         (app-data/get-version-spec-for-environment environment)
                                                         app-session {})
            environment (assoc environment :commit-id commit-id)
            _ (checkpoint! "YAML loaded")
            meta (:metadata app-map)
            app-map (assoc-in (dissoc app-map :metadata) [:theme :vars] (:theme-vars style))
            app-origin (:app-origin req)

            app-services (:services app-map)
            get-service (fn [url] (first (filter #(= (:source %) url) app-services)))
            google-service (get-service "/runtime/services/google.yml")
            google-api-key (utils/or-str (-> google-service :client_config :api_key)
                                         (:maps-api-key conf/google-client-config))
            _ (checkpoint! "Google configured")

            {:keys [body-class]} (app-data/get-extra-rendering-info app-id app-session {})
            runnerVersion (if (get-in app-map [:runtime_options :preview_v3])
                            3
                            (get-in app-map [:runtime_options :version] 0))
            client-params (assoc client-params :runtimeVersion runnerVersion)

            new-ui-runtime? (>= runnerVersion 3)]

        (log/debug "Serving app" app-id)

        (sessions/resolve-ambiguous-client-type! app-session "browser")
        (sessions/ensure-logged! app-session)

        ;; We are loading this app from scratch, so we don't care if our old session has expired.
        ;; We also reset the environment in this session to match what the URL told us, so that refreshing
        ;; the page gets you the latest version
        (swap! app-session assoc
               :anvil.runtime/replacement-session false
               :environment environment)
        (reload-anvil-cookies! (:app-session req) req)
        (metrics/inc! :api/runtime-serve-app-total)

        (sessions/persist! app-session)

        (-> (serve-templated-html req (runtime-client-resource req (if new-ui-runtime? "/runner2.html" "/runner.html"))
                                  {"{{body-class}}"         (or body-class "")
                                   "{{app-name}}"           (hiccup-util/escape-html
                                                              (util/or-str (:title meta) (:name app-info) (:name app-map)))
                                   "{{app-title}}"          (hiccup-util/escape-html
                                                              (util/or-str (:title meta) (:name app-info) (:name app-map)))
                                   "{{ms-tile-image}}"      (image-from-metadata app-origin meta :logo_img "/mstile-144x144.png")
                                   "{{favicon}}"            (image-from-metadata app-origin meta :logo_img "/favicon-96x96.png")
                                   "{{social-image}}"       (image-from-metadata app-origin meta :logo_img "/img/logo-square-padded.png")
                                   "{{social-description}}" (render-app-description (:description meta))
                                   "{{canonical-url}}"      (hiccup-util/escape-html app-origin)
                                   "{{anvil-version}}"      conf/anvil-version
                                   "{{google-api-key}}"     (or google-api-key "")
                                   "{{session-token}}"      (or (:session-url-token req)
                                                                (get-in @app-session [::sessions/tokens :url])
                                                                (get-in @app-session [::sessions/tokens :burned])
                                                                "")
                                   "{{manifest-url}}"       (str (hiccup-util/escape-html app-origin) "/_/manifest.json")
                                   "{{theme-color}}"        (:primary-color style)
                                   "{{shim-css}}"           (:css-shims style)
                                   "{{theme-css}}"          (:css style)
                                   "{{root-vars}}"          (:root-vars style)
                                   "{{head-html}}"          head-html
                                   "{{extra-snippets}}"     (get-service-snippets app-map)
                                   "{{app-info-object}}"    (util/write-json-str (runtime-util/get-runtime-app-info environment))
                                   "{{load-app-code}}"      (str "\n$(function() {"
                                                                 (get-service-code app-map)
                                                                 "window.loadApp(" (util/write-json-str (merge {"app"       app-map
                                                                                                                "appId"     app-id
                                                                                                                "appOrigin" app-origin}
                                                                                                               client-params)) ", anvilServicePreloadModules);"
                                                                 "const loadAppAfter = window.anvil._loadAppAfter || [];"
                                                                 "Promise.all(loadAppAfter).then(function() {"
                                                                 (condp = action
                                                                   :run-app (let [startup (or (:startup app-map) {:type "form" :module (:startup_form app-map)})]
                                                                              (if (= "module" (:type startup))
                                                                                (str "window.openMainModule(" (util/write-json-str (:module startup)) ");")
                                                                                (str "window.openForm(" (util/write-json-str (:module startup)) ")")))
                                                                   :print (str "window.printComponents(" (util/write-json-str print-id) "," (util/write-json-str print-key) ");"))
                                                                 "});"
                                                                 "});")})
            (resp/header "Referrer-Policy" "no-referrer")
            (resp/header "X-UA-Compatible" "IE=edge")
            (resp/header "Content-Type" "text/html")
            (resp/header "X-Anvil-Cacheable" true)
            (resp/header "Access-Control-Expose-Headers" "X-Anvil-Cacheable")
            (resp/set-cookie "anvil-test-cookie" true)
            (#(if-not (contains? (:cookies req) (:shared conf/app-cookie-names))
                (assoc-in % [:cookies (:shared conf/app-cookie-names)] (merge {:value     "x"
                                                                               :max-age   2147483647
                                                                               :domain    (:shared-cookie-domain @(:app-session req))
                                                                               :path      "/"
                                                                               :http-only true}
                                                                              (when conf/force-secure-cookies?
                                                                                {:same-site :none
                                                                                 :secure    true})))
                %))
            (#(if-let [restrict-embedding-origins (get-embedding-restrictions app-map)]
                (-> %
                    (resp/header "X-Frame-Options" (str "allow-from " restrict-embedding-origins))
                    (resp/header "Content-Security-Policy" (str "frame-ancestors " restrict-embedding-origins)))
                %))))
      (catch :anvil/app-loading-error e
        (app-500 req (:message e))))))

(defn serve-api-request
  ([path request] (serve-api-request path request "http"))
  ([path request task-prefix]
   (log/trace "API hit" request)
   (log/trace "Session:" (:app-session request))
   (sessions/resolve-ambiguous-client-type! (:app-session request) "http")
   (sessions/ensure-logged! (:app-session request))
   (with-channel request channel
     (try+
       (let [anvil-cookies-updated? (atom false)

             responded? (atom false)

             with-safe-anvil-cookies (fn [resp headers]
                                       (let [resp-with-safe-headers (reduce (fn [resp [k v]]
                                                                              (let [h (.toLowerCase (name k))]
                                                                                (cond
                                                                                  (and (= h "set-cookie")
                                                                                       ((set (vals conf/app-cookie-names)) (first (string/split v #"[=\s]+"))))
                                                                                  resp

                                                                                  (= h "content-type")
                                                                                  ; Because this has already been set, we need to explicitly override it
                                                                                  (assoc-in resp [:headers "Content-Type"] v)

                                                                                  :else
                                                                                  (update-in resp [:headers h] conj v))))
                                                                            resp headers)]
                                         (if (and @anvil-cookies-updated? (not (:cross-origin request)))
                                           (-> resp-with-safe-headers
                                               (with-anvil-cookies (:app-session request))
                                               (ring.middleware.cookies/cookies-response))
                                           resp-with-safe-headers)))

             _ (when (:new-session request)
                 (swap! (:app-session request) assoc-in [:client :type] :http))

             method (.toUpperCase (name (:request-method request)))

             session (:app-session request)
             _ (reload-anvil-cookies! session request)

             ;; TODO: Work out how to get the path and method into session data that may have already been logged.
             [session alternate-session] (if (:cross-origin request)
                                           ;; N.B This session doesn't get logged immediately, because it might be thrown away if the http_endpoint allows cross-origin sessions.
                                           ;; If it needs logging (because we ended up using it), that will happen in anvil.private.switch_session!
                                           [(sessions/new-unlogged-session-with-state (sessions/new-session-state-from-request request :http)
                                                                                      (-> (app-log/log-data-from-ring-request request)
                                                                                          (assoc :path path
                                                                                                 :method method)))
                                            session]
                                           [session nil])

             dispatcher-request (promise)

             trace-id nil                                   ;; TODO NEW TRACE API
             return-path {:update!
                          (fn [{:keys [output set-cookie] :as r}]
                            (cond
                              set-cookie
                              (reset! anvil-cookies-updated? true)

                              (string? output)
                              ;; TODO which session do we log this into?!
                              (app-log/record-event! session trace-id "print" output nil)))

                          :respond!
                          (fn [{{:keys [status body headers] :or {headers []}} :response :keys [response error] :as r}]

                            (when (get-in @(sessions/ephemeral-cache session) [::sessions/dirty])
                              (sessions/notify-session-update! session))

                            (binding [rpc-util/*environment* (:environment request)]
                              (try+
                                (cond
                                  (= (:type error) "RateLimitExceeded")
                                  (send! channel (-> {:body (str "Rate limit exceeded: " (:message error))}
                                                     (resp/status 429)
                                                     (resp/content-type "text/plain")))

                                  error
                                  ;; NoServerFunctionError -> 404; anything else -> 500
                                  (if (and (= (:type error) "anvil.server.NoServerFunctionError")
                                           (re-find #"http:" (:message error)))
                                    (do
                                      (send! channel (-> {:body "No matching API endpoint"}
                                                         (resp/status 404)
                                                         (resp/content-type "text/plain")))
                                      ;; TODO which session do we log this into?
                                      (let [error (assoc error :message (str "API request routing failed. No @anvil.server.http_endpoint exists with path matching '" (subs path 0 (min (count path) 100)) (when (> (count path) 100) "...") "'"))]
                                        (app-log/record-event! session trace-id "err" (str (:type error) ": " (:message error)) error)))

                                    (do
                                      (send! channel (-> {:body "An exception was raised. Check the application logs for details."}
                                                         (resp/status 500)
                                                         (resp/content-type "text/plain")))
                                      (when-not @responded?
                                        (app-log/record-event! session trace-id "err" (str (:type error) ": " (:message error)) error))))

                                  (or (instance? Media body) (instance? anvil.dispatcher.types.LazyMedia body))
                                  (worker-pool/run-task! {:type :task
                                                          :name ::serve-api-lazy-media
                                                          :tags (worker-pool/get-task-tags-for-http-request request)}
                                    (try+
                                      (send! channel (-> {:body   (blocking-hacks/?->InputStream @dispatcher-request body)
                                                          :status status}
                                                         (resp/content-type (.getContentType ^MediaDescriptor body))
                                                         (with-safe-anvil-cookies headers)))
                                      (catch Exception e
                                        (let [error-id (random/hex 6)]
                                          (log/error e "Error in app API Lazy Media response:" error-id)
                                          (send! channel (-> {:body (str "Internal server error serving Lazy Media: " error-id)}
                                                             (resp/status 500)
                                                             (resp/content-type "text/plain")))))))

                                  (instance? ChunkedStream body)
                                  (do
                                    (send! channel (-> {:status status}
                                                       (resp/content-type (.getContentType ^MediaDescriptor body))
                                                       (with-safe-anvil-cookies headers)) false)
                                    (types/consume ^ChunkedStream body
                                                   (fn [chunk-idx last-chunk? data]
                                                     (send! channel {:body (ByteArrayInputStream. data)} last-chunk?))))

                                  (string? body)
                                  (send! channel (-> {:body   body,
                                                      :status status}
                                                     (resp/content-type "text/plain; charset=utf-8")
                                                     (with-safe-anvil-cookies headers)))

                                  :else
                                  (do
                                    ((fn check-json [value path]
                                       (let [fail! #(throw+ {:anvil/server-error (format "Cannot send a %s object over HTTP as response%s.\nYou must return either Media, a string, or a JSON-compatible object (lists/dicts/strings/numbers/None).\n"
                                                                                         (.getSimpleName (.getClass value))
                                                                                         (apply str (for [p (reverse path)] (str "[" (pr-str p) "]"))))})]
                                         (cond
                                           (or (string? value) (number? value) (nil? value))
                                           :ok

                                           (and (map? value) (not (record? value)))
                                           (doseq [[k v] value] (check-json v (cons (name k) path)))

                                           (or (seq? value) (vector? value))
                                           (doall (map-indexed #(check-json %2 (cons %1 path)) value))

                                           :else
                                           (fail!))))
                                     body nil)
                                    (send! channel (-> {:body   (util/write-json-str body),
                                                        :status status}
                                                       (resp/content-type "application/json")
                                                       (with-safe-anvil-cookies headers)))))

                                (reset! responded? true)
                                (catch :anvil/server-error e
                                  (log/trace e)
                                  (send! channel (-> {:body (:anvil/server-error e)}
                                                     (resp/status 500)
                                                     (resp/content-type "text/plain"))))
                                (catch Exception _e
                                  (log/trace _e)
                                  (send! channel (-> {:body   "This response cannot be transmitted over HTTP. You must return either Media, a string, or a JSON-compatible object (lists/dicts/strings/numbers/None)"
                                                      :status 500}
                                                     (resp/content-type "text/plain")))))))}

             body-media (when (:body request)
                          ; For some reason, if there's no Content-Type header, the body has already been read. Reset it.
                          (.reset (:body request))
                          (InputStreamMedia. (get-in request [:headers "content-type"])
                                             (:body request)
                                             (second (re-matches #"attachment;\s*filename=\"(.*)\"\s*"
                                                                 (get-in request [:headers "content-disposition"] "")))
                                             (get-in request [:headers "content-length"])))
             authorization (get-in request [:headers "authorization"] "")
             [[_ username password]] (try
                                       (when-let [m (re-matches #"\s*Basic\s+(.+)" authorization)]
                                         (-> m
                                             ^String second
                                             (.getBytes "UTF-8")
                                             Base64/decodeBase64
                                             (String. "UTF-8")
                                             (#(re-seq #"([^:]*):(.*)" %))))
                                       (catch Exception e
                                         (throw+ {::api-error (str "Invalid Authorization header for HTTP Basic auth: " authorization)})))


             headers-without-app-cookies (update-in (:headers request) ["cookie"] (fn [cookies-header]
                                                                                    (when cookies-header
                                                                                      (apply str (interpose "; " (filter
                                                                                                                   #(not ((set (vals conf/app-cookie-names)) (first (string/split % #"="))))
                                                                                                                   (string/split cookies-header #"; ")))))))]

         (deliver dispatcher-request {:call          {:func (str task-prefix ":" path),
                                                      :args [] :kwargs {:method                    method
                                                                        :path                      path
                                                                        :origin                    (get headers-without-app-cookies "origin")
                                                                        :query_params              (:query-params request)
                                                                        :form_params               (:form-params request)
                                                                        :headers                   headers-without-app-cookies
                                                                        :remote_address            (:remote-addr request)
                                                                        :body                      body-media
                                                                        :username                  username
                                                                        :password                  password
                                                                        :same_app_alternate_origin (:alternate-app-origin request)}}
                                      :app-id        (:app-id request), :app-origin (:app-origin request)
                                      :environment   (or (:environment-from-url request) (:environment request))
                                      :session-state session
                                      :origin        :http_endpoint
                                      :call-stack    (list {:type :http})
                                      :thread-id     (str "endpoint-" (:app-id request) "-" (random/hex 16))
                                      :use-quota?    true
                                      :anvil.dispatcher/alternate-session alternate-session})
         (metrics/inc! :api/runtime-serve-api-total)
         (dispatcher/dispatch! @dispatcher-request
                               return-path))

       (catch ::api-error e
         (send! channel (-> {:body (::api-error e)}
                            (resp/status 400)
                            (resp/content-type "text/plain"))))
       (catch :anvil/server-error e
         (log/trace "Serving error" e)
         (send! channel (-> {:body (:anvil/server-error e)}
                            (resp/status 500)
                            (resp/content-type "text/plain"))))

       (catch :anvil/app-loading-error e
         (let [error-id (random/hex 6)]
           (log/warn (str "App dependency error when loading app " (:app-id request) " for API call to " path ": " error-id))
           (send! channel (-> {:body (str "Internal server error: " error-id)}
                              (resp/status 500)
                              (resp/content-type "text/plain")))))

       (catch Exception e
         (let [error-id (random/hex 6)]
           (log/error e "Error in app API:" error-id)
           (send! channel (-> {:body (str "Internal server error: " error-id)}
                              (resp/status 500)
                              (resp/content-type "text/plain")))))))))

(defn serve-lazy-media [manager media-key media-id nodl request]
  (log/trace "Request for media" request)

  ; TODO: We should use our quotas directly here. Currently, only user-lazy-media uses the dispatcher, so only that type of
  ;       lazy media has rate limiting. Google drive file downloads do not.

  ;; TODO: Lazy media sessions should be loaded by ID (i.e. use an sid parameter rather than using the session from the
  ;;       request, then load the app from the session rather than the request)
  (with-channel request channel
    (try+
      (let [app (get-app-from-request request false)]
        (worker-pool/run-task! {:type :task
                                :name ::serve-lazy-media
                                :tags (worker-pool/get-task-tags-for-http-request request)}
          (try+
            (when-let [m (lazy-media/get-lazy-media {:app-id        (:app-id request)
                                                     :app           (:content app)
                                                     :session-state (:app-session request)
                                                     :environment   (:environment request)}
                                                    manager media-key media-id)]
              (send! channel (-> {:body (.getInputStream ^Media m)}
                                 (resp/status 200)
                                 (#(if-let [l (.getLength ^Media m)] (resp/header % "Content-Length" l) %))
                                 (resp/header "Content-Disposition" (if nodl nil (str "attachment"
                                                                                      (when-let [name (.getName ^MediaDescriptor m)]
                                                                                        (str ";filename=" name)))))
                                 (resp/content-type (.getContentType ^MediaDescriptor m)))))
            (catch :anvil/server-error e
              (log/trace (:throwable &throw-context) (:anvil/server-error e))
              (send! channel (-> {:body (:anvil/server-error e)}
                                 (resp/status 500)
                                 (resp/content-type "text/plain"))))

            (catch :lm/rate-limited e
              (send! channel (-> {:body (str "Rate limit exceeded: " (:lm/rate-limited e))}
                                 (resp/status 429)
                                 (resp/content-type "text/plain"))))

            (catch :anvil/lazy-media-error e
              (send! channel (-> {:body (str "Bad request: " (:anvil/lazy-media-error e))}
                                 (resp/status 400)
                                 (resp/content-type "text/plain"))))

            (catch Exception e
              (let [error-id (random/hex 6)]
                (log/error e "Error getting lazy media:" error-id)
                (send! channel (-> {:body (str "Internal server error: " error-id)}
                                   (resp/status 500)
                                   (resp/content-type "text/plain"))))))))
      (catch :anvil/app-loading-error e
        (let [error-id (random/hex 6)]
          (log/error (:throwable &throw-context) "App dependency error when getting lazy media:" error-id)
          (send! channel (-> {:body (str "Internal server error: " error-id)}
                             (resp/status 500)
                             (resp/content-type "text/plain"))))))))

; This is the real ID. Converted from id-or-alias before this point.
(defroutes app-routes
  (GET "/_/email-confirm/:email/:email-key" [email email-key :as request]
    (when-let [app (get-app-from-request request)]
      (if (user-service/confirm-email app (:environment request) email email-key)
        (serve-templated-html request (runtime-client-resource request "/user_email_confirmed.html")
                              {"{{email-address}}" (hiccup-util/escape-html email)})
        (resp/redirect (:app-origin request)))))

  (GET "/_/email-pw-reset/:email/:email-key" [email email-key :as request]
    (when-let [app (get-app-from-request request)]
      (when (user-service/email-password-reset-key-valid? app (:environment request) email email-key)
        (serve-templated-html request (runtime-client-resource request "/user_email_password_reset.html")
                              {"{{email-address}}" (hiccup-util/escape-html email)
                               "{{error}}" ""}))))

  (GET ["/_/:path/:token" :path #"login|reset_password" :token #".*"] [token :as request]
    (when-let [app (get-app-from-request request)]
      (when (user-service/do-login-with-token app (request :environment) (request :app-session) (codec/url-decode token))
        (sessions/persist! (request :app-session))
        (resp/redirect (str (:app-origin request) "?s=" (sessions/url-token (request :app-session)))))))

  (POST "/_/email-pw-reset/:email/:email-key" [email email-key password :as request]
    (when-let [app (get-app-from-request request)]
      (try+
        (when (user-service/reset-email-password! app (:environment request) email email-key password)
          (serve-templated-html request (runtime-client-resource request "/user_email_password_reset_done.html")
                                {"{{email-address}}" (hiccup-util/escape-html email)}))
        (catch :anvil/server-error e
          (serve-templated-html request (runtime-client-resource request "/user_email_password_reset.html")
                                {"{{email-address}}" (hiccup-util/escape-html email)
                                 "{{error}}"         (str "<div class=\"alert alert-danger\" role=\"alert\"><b>" (:anvil/server-error e) "</b></div>")})))))

  (GET "/_/print/:print-id/:print-key" [print-id print-key :as request]
    (when-let [app (get-app-from-request request)]
      (serve-app request {} {:action :print, :print-id print-id, :print-key print-key})))

  (ANY "/_/logout" request
    (sessions/delete! (:app-session request))
    (-> {:body            ""}
        (resp/status 200)
        (resp/content-type "text/plain")
        (resp/header "Access-Control-Allow-Credentials" "true")))

  (GET ["/_/theme/:asset-name", :asset-name #".*"] [asset-name :as request]
    (let [app-content (:content (get-app-from-request request))]
      (when-let [asset (->> (app-data/get-all-assets app-content)
                            (filter #(= (:name %) asset-name))
                            (first))]
        (log/trace "Serving an asset: " (:name asset))
        (let [mime-type (mime-type/ext-mime-type asset-name)]
          (-> (Base64/decodeBase64 ^String (:content asset))
              (ByteArrayInputStream.)
              (resp/response)
              (resp/header "X-Anvil-Cacheable" true)
              (resp/header "Access-Control-Expose-Headers" "X-Anvil-Cacheable")
              (resp/content-type mime-type))))))

  (ANY ["/_/lm/:manager/:media-key/:media-id/*"] [manager media-key media-id nodl :as request]
    (serve-lazy-media manager media-key media-id nodl request))

  (ANY ["/_/api:path", :path #".*"] [path :as request]
    (serve-api-request path request))

  (ANY ["/_/ws:old-key" :old-key #".*"] request
    (if-let [other-origin (:cross-origin request)]
      (do (log/warn (str "Origin header mismatch on websocket connection to " (:app-origin request) ": " other-origin))
          (-> (resp/response "Invalid origin")
              (resp/status 403)))
      (try+
        (when-let [app (get-app-from-request request false)]
          (browser-ws/ws-handler (assoc-in request [:environment :commit-id] (:version app)) (:content app)))
        (catch :anvil/app-loading-error e
          (log/error (:throwable &throw-context) "App dependency error when connecting websocket")))))

  (GET "/_/service-worker" req
    (-> (slurp (runtime-client-resource req "/dist/sw.bundle.js"))
        (resp/response)
        (resp/header "Service-Worker-Allowed" (hiccup-util/escape-html (:app-origin req)))
        (resp/content-type "application/javascript")))

  (POST "/_/request_cookies" req
    (with-anvil-cookies (resp/response "") (:app-session req)))


  (GET "/_/client_auth_redirect" request
    (if (:anvil.runtime/replacement-session @(:app-session request))
      (resp/redirect (str conf/static-root-url "/runtime-new/runtime/client_auth_error.html#" (codec/url-encode "SESSION_EXPIRED")))
      (let [params (:params request)
            app (:content (get-app-from-request request))

            ;; Can only use this if we have added and configured the google service for our app.
            ;; Look up its config.

            google-service (first (filter #(= (:source %) "/runtime/services/google.yml") (:services app)))
            google-client-id (or (get-in google-service [:server_config :client_id]) (and (:custom? conf/google-client-config) (:client-id conf/google-client-config)))

            custom-google-config? (not-empty google-client-id)

            params (if custom-google-config?
                     params
                     (assoc params :scope "https://www.googleapis.com/auth/userinfo.email"))

            offline-access (and (not= "" google-client-id) google-client-id)

            google-client-id (if custom-google-config?
                               google-client-id
                               (:client-id conf/google-client-config))

            redirect (str (if (and custom-google-config?
                                   (not (get-in request [:environment :allow-debug?])) ;; When debugging, use the shared redirect regardless
                                   (get-in google-service [:server_config :app_origin_redirect]))
                            (:app-origin request)
                            conf/runtime-common-url) "/_/client_auth_callback")]

        (if (empty? google-client-id)

          (resp/redirect (str conf/static-root-url "/runtime-new/runtime/client_auth_error.html#" (codec/url-encode "No client ID specified in Google API service config.")))

          (let [login-response (oauth/get-login-response request (sessions/get-id (:app-session request)) google-client-id redirect (:scope params) offline-access)]
            ;; Need to get csrf-token out of the cookie session and into app-session
            (swap! (:app-session request) assoc
                   ::google-csrf-token (-> login-response :session :anvil.core.google-oauth2/csrf-token)
                   ::google-redirect redirect)              ;; Saves working out the redirect again in the callback.
            (sessions/notify-session-update! (:app-session request))
            login-response)))))

  (GET "/_/client_auth_id_token" request
    (resp/response (-> @(:app-session request) :google :user-tokens :id-token)))

  (GET "/_/facebook_auth_redirect" request
    (if (:anvil.runtime/replacement-session @(:app-session request))
      (resp/redirect (str conf/static-root-url "/runtime-new/runtime/facebook_auth_error.html#" (codec/url-encode "SESSION_EXPIRED")))
      (let [csrf-token (random/hex 60)

            app (:content (get-app-from-request request))

            facebook-service (first (filter #(= (:source %) "/runtime/services/facebook.yml") (:services app)))
            facebook-client-id (or (get-in facebook-service [:server_config :app_id])
                                   (and (:custom? conf/facebook-client-config) (:app-id conf/facebook-client-config)))

            app-id-provided? (not (or (= "" facebook-client-id) (nil? facebook-client-id)))

            facebook-client-id (if app-id-provided?
                                 facebook-client-id
                                 (:app-id conf/facebook-client-config))

            requested-scopes (-> request :params :scopes)

            scope (if app-id-provided?
                    (str "email," requested-scopes)
                    "email")]

        (if (and requested-scopes
                 (not= "" requested-scopes)
                 (not app-id-provided?))
          (resp/redirect (str conf/static-root-url "/runtime-new/runtime/facebook_auth_error.html#" (codec/url-encode "To specify custom permissions, you must provide an app ID in the Facebook service config.")))

          (do (swap! (:app-session request) assoc ::facebook-csrf-token csrf-token)
              (sessions/notify-session-update! (:app-session request))
              (resp/redirect (str "https://www.facebook.com/v3.2/dialog/oauth?"
                                  "&client_id=" facebook-client-id
                                  "&redirect_uri=" (codec/url-encode (str conf/runtime-common-url "/_/facebook_auth_callback"))
                                  "&state=" (sessions/get-id (:app-session request)) "G" (codec/url-encode csrf-token)
                                  "&scope=" (codec/url-encode scope)
                                  ;"&auth_type=reauthenticate" ; This causes Colette to land in an infinite loop being asked for her password.
                                  "&display=popup")))))))

  (GET "/_/microsoft_auth_redirect" request

    ;; We implement OpenID Connect here: https://docs.microsoft.com/en-us/azure/active-directory/develop/active-directory-v2-protocols-oidc

    ;; To register an app, visit https://apps.dev.microsoft.com/#/appList
    ;; Set redirect_url to https://anvil.works/apps/_/microsoft_auth_callback
    ;; No need for an Application Secret if just using login.
    (if (:anvil.runtime/replacement-session @(:app-session request))
      (resp/redirect (str conf/static-root-url "/runtime-new/runtime/microsoft_auth_error.html#" (codec/url-encode "SESSION_EXPIRED")))
      (let [csrf-token (random/hex 60)
            nonce (random/hex 60)

            app (:content (get-app-from-request request))

            microsoft-service (first (filter #(= (:source %) "/runtime/services/anvil/microsoft.yml") (:services app)))
            application-id (or (get-in microsoft-service [:server_config :application_id])
                               (and (:custom? conf/microsoft-client-config) (:application-id conf/microsoft-client-config)))
            additional-scopes (get-in microsoft-service [:server_config :additional_oauth_scopes])
            app-secret-provided? (or (get-in microsoft-service [:server_config :application_secret_enc])
                                     (get-in microsoft-service [:server_config :application_secret])
                                     (and (:custom? conf/microsoft-client-config) (:application-secret conf/microsoft-client-config)))

            app-id-provided? (not (or (= "" application-id) (nil? application-id)))

            tenant-id (if app-id-provided?
                        (or (get-in microsoft-service [:server_config :tenant_id])
                            (and (:custom? conf/microsoft-client-config) (:tenant-id conf/microsoft-client-config)))
                        (:tenant-id conf/microsoft-client-config))

            application-id (if app-id-provided?
                             application-id
                             (:application-id conf/microsoft-client-config))

            requested-scopes (.trim (str (-> request :params :scopes) " " additional-scopes))

            scope (if app-id-provided?
                    (str "openid email profile offline_access " requested-scopes)
                    "openid email profile")

            ;_ (prn "Scope:" app-id-provided? application-id microsoft-service scope)
            ]

        (if (and requested-scopes
                 (not-empty (-> request :params :scopes))
                 (or (not app-id-provided?)
                     (not app-secret-provided?)))
          (resp/redirect (str conf/static-root-url "/runtime-new/runtime/microsoft_auth_error.html#" (codec/url-encode "To specify custom permissions (scopes), you must provide an Application ID and Application Secret in the Microsoft service config.")))

          (do (swap! (:app-session request) assoc ::microsoft-csrf-token csrf-token ::microsoft-nonce nonce)
              (sessions/notify-session-update! (:app-session request))
              (resp/redirect (str "https://login.microsoftonline.com/" (URLEncoder/encode (util/or-str tenant-id "common")) "/oauth2/v2.0/authorize?"
                                  "&client_id=" application-id
                                  "&response_type=" (if app-id-provided? "code" "id_token")
                                  "&redirect_uri=" (codec/url-encode (str conf/runtime-common-url "/_/microsoft_auth_callback"))
                                  "&response_mode=form_post"
                                  "&state=" (sessions/get-id (:app-session request)) "G" (codec/url-encode csrf-token)
                                  "&nonce=" (codec/url-encode nonce)
                                  "&scope=" (codec/url-encode scope))))))))

  (GET "/_/saml-sp-metadata" request
    (let [app (:content (get-app-from-request request))
          saml-service (first (filter #(= (:source %) "/runtime/services/anvil/saml.yml") (:services app)))
          settings ^Saml2Settings (saml/get-settings (:server_config saml-service) (:app-info request))
          metadata (.getSPMetadata settings)
          errors (Saml2Settings/validateMetadata metadata)]
      (if (empty? errors)
        (-> metadata
            (resp/response)
            (resp/content-type "application/xml")
            (resp/header "content-disposition" (str "attachment; filename=SAML Metadata - " (clojure.string/replace (str (:name app)) #"[^A-Za-z0-9\. ]" "") ".xml")))
        (resp/response {:errors errors}))))

  (GET "/_/saml_auth_redirect" request
    (try
      (let [app (:content (get-app-from-request request))
            saml-service (first (filter #(= (:source %) "/runtime/services/anvil/saml.yml") (:services app)))
            server-config (:server_config saml-service)
            settings (saml/get-settings server-config (:app-info request))

            authn-request (AuthnRequest. settings (boolean (:force_authentication server-config)) false true)
            sso-url (.getIdpSingleSignOnServiceUrl settings)

            saml-request (.getEncodedAuthnRequest authn-request)
            csrf-token (random/hex 60)

            relay-state (str (sessions/get-id (:app-session request)) "G" csrf-token)

            query-string (str "SAMLRequest=" (util/real-actual-genuine-url-encoder saml-request)
                              "&RelayState=" (util/real-actual-genuine-url-encoder relay-state)
                              "&SigAlg=" (util/real-actual-genuine-url-encoder (.getSignatureAlgorithm settings)))

            signature (saml/sign-request query-string settings)

            redirect-target (str sso-url
                                 "?" query-string
                                 "&Signature=" (util/real-actual-genuine-url-encoder signature))]
        (swap! (:app-session request) assoc ::saml-csrf-token csrf-token)
        (sessions/notify-session-update! (:app-session request))
        (log/info "SAML auth redirect in session" (pr-str (sessions/get-id (:app-session request))) "with token" (pr-str csrf-token))
        (resp/redirect redirect-target))
      (catch Exception e
        (-> (resp/response
              (populate-template (runtime-client-resource request "/auth_result.html")
                                 {"{{canonical-url}}" (hiccup-util/escape-html (:app-origin @(:app-session request)))
                                  "{{callback-fn}}"   "samlAuthErrorCallback"
                                  "{{args-json}}"     (json/write-str {:message (str "SAML Redirect failed: " (.getMessage e))})}))
            (resp/content-type "text/html")
            (resp/status 200)))))

  (POST "/_/log" request
    ;; Absorb this by default
    {:status 200})

  (GET "/_/check-app-online" req
    {:status 200})

  (GET "/_/get_stripe_publishable_keys" []
    (resp/response {:live (conf/stripe-client-config :live-publishable-key)
                    :test (conf/stripe-client-config :test-publishable-key)}))

  (GET "/_/validate-app/:challenge" [challenge :as req]
    (when-let [nonce (try
                       (secrets/decrypt-str-with-global-key :domain-validation challenge)
                       (catch Exception _ nil))]
      (resp/response {:app-id (:app-id req)
                      :nonce nonce})))

  (GET "/_/manifest.json" {:keys [app-origin] :as req}
    (let [[app-info app-map style _head-html _commit-id] (app-data/sanitised-app-and-style-for-client (:app-id req) (app-data/get-version-spec-for-environment (:environment req)))]
      (-> (populate-template (runtime-client-resource req "/manifest.json")
                             {"{{app-name}}"           (:name app-info)
                              "{{social-description}}" (render-app-description (get-in app-map [:metadata :description]))
                              "{{canonical-url}}"      (hiccup-util/escape-html app-origin)
                              "{{icon}}"               (image-from-metadata app-origin (:metadata app-map) :logo_img "/icon-512x512.png")

                              "{{theme-color}}"        (:primary-color style)
                              "{{background-color}}"   (if (and (:primary-color style)
                                                                (= (.toLowerCase (:primary-color style)) "#2ab1eb"))
                                                         "white"
                                                         (:primary-color style))})
          (resp/response)
          (resp/content-type "application/json"))))

  ;; TODO: This is where in-app routing will be dealt with. For now, only route /
  ;; Also matches no-trailing-slash. See https://github.com/weavejester/compojure/issues/153#issuecomment-222545162.

  (ANY ["/.well-known:path", :path #".*"] [path :as request]
    (serve-api-request path request "http-wellknown"))

  (GET "/" request
    (serve-app request {} {:action :run-app})))


(defroutes runtime-common-routes

  ;; These routes are unusual - they are accessible at /runtime/... on all origins!
  (GET "/_/client_auth_callback" req
    ;; in the Google developer console, so we must redirect back to the same place for every app.
    (let [session-id (clojure.string/replace (or (-> req :params :state) "") #"G[^G]*$" "")
          app-session (sessions/load-session-by-id-without-authentication session-id)]

      (if-not app-session
        (resp/redirect (str conf/static-root-url "/runtime-new/runtime/client_auth_error.html#" (codec/url-encode "SESSION_EXPIRED")))
        (let [session-state (sessions/deref-db app-session)
              request (merge req {:app-session app-session} (select-keys session-state [:app-id :app-info :environment]))
              app (:content (get-app-from-request request))
              google-service (first (filter #(= (:source %) "/runtime/services/google.yml") (:services app)))
              google-client-id (or (get-in google-service [:server_config :client_id])
                                   (and (:custom? conf/google-client-config) (:client-id conf/google-client-config)))

              custom-google-config? (not-empty google-client-id)

              google-client-secret (if custom-google-config?
                                     (or (when-let [encrypted-client-secret (get-in google-service [:server_config :client_secret_enc])]
                                           (:value (secrets/get-global-app-secret-value (:app-info session-state) "google-service/client-secret" encrypted-client-secret)))
                                         (get-in google-service [:server_config :client_secret])
                                         (and (:custom? conf/google-client-config) (:client-secret conf/google-client-config)))
                                     (:client-secret conf/google-client-config))

              google-client-id (if custom-google-config?
                                 google-client-id
                                 (:client-id conf/google-client-config))]

          (log/trace "Client auth callback in session" session-id (str "(app " (get request :app-id) ")"))

          (try
            (let [tokens (oauth/process-callback (-> req :params :code)
                                                 (-> req :params :state)
                                                 (::google-csrf-token session-state)
                                                 google-client-id
                                                 google-client-secret
                                                 (::google-redirect session-state))]

              (log/debug "CLIENT AUTH COMPLETE")
              (log/trace (with-out-str (pprint tokens)))

              (swap! app-session #(assoc-in % [:google :user-tokens] tokens))

              (sessions/notify-session-update! app-session)

              (-> (resp/response (-> (slurp (runtime-client-resource request "/client_auth_success.html"))
                                     (.replace "{{canonical-url}}" ^String (hiccup-util/escape-html (:app-origin session-state)))))
                  (resp/content-type "text/html")
                  (resp/status 200)))

            (catch Exception e
              (log/error e "Error in Google auth callback")
              (resp/redirect (str conf/static-root-url "/runtime-new/runtime/client_auth_error.html#" (codec/url-encode (.getMessage e))))))))))

  (GET "/_/facebook_auth_callback" req
    (let [redirect-uri (str conf/runtime-common-url "/_/facebook_auth_callback")
          session-id (clojure.string/replace (or (-> req :params :state) "") #"G[^G]*$" "")
          app-session (sessions/load-session-by-id-without-authentication session-id)]
      (if-not app-session
        (resp/redirect (str conf/static-root-url "/runtime-new/runtime/facebook_auth_error.html#" (codec/url-encode "SESSION_EXPIRED")))
        (let [session-state (sessions/deref-db app-session)
              request (merge req {:app-session app-session} (select-keys session-state [:app-id :app-info :environment]))
              app (:content (get-app-from-request request))
              facebook-service (first (filter #(= (:source %) "/runtime/services/facebook.yml") (:services app)))
              facebook-client-id (or (get-in facebook-service [:server_config :app_id])
                                     (and (:custom? conf/facebook-client-config) (:app-id conf/facebook-client-config)))

              facebook-client-secret (if (or (= "" facebook-client-id) (nil? facebook-client-id))
                                       (:app-secret conf/facebook-client-config)
                                       (or (when-let [encrypted-app-secret (get-in facebook-service [:server_config :app_secret_enc])]
                                             (:value (secrets/get-global-app-secret-value (:app-info session-state) "facebook-service/app-secret" encrypted-app-secret)))
                                           (get-in facebook-service [:server_config :app_secret])
                                           (and (:custom? conf/facebook-client-config) (:app-secret conf/facebook-client-config))))

              facebook-client-id (if (or (= "" facebook-client-id) (nil? facebook-client-id))
                                   (:app-id conf/facebook-client-config)
                                   facebook-client-id)]
          (try
            (let [provided-csrf-token (last (.split ^String (-> req :params :state) "G"))]

              ;; First, check the CSRF token matches the one we put in the session
              (if (not= provided-csrf-token (::facebook-csrf-token session-state))

                ;; CSRF does not match. Fail.
                (throw (Exception. "CSRF CHECK FAILED"))

                ;; CSRF matches. Start by exchanging the auth code for an access token
                (let [body-json (:body @(http/post "https://graph.facebook.com/v3.2/oauth/access_token"
                                                   {:keepalive -1
                                                    :form-params {:code          (-> req :params :code)
                                                                  :client_id     facebook-client-id
                                                                  :client_secret facebook-client-secret
                                                                  :redirect_uri  redirect-uri
                                                                  :grant_type    "authorization_code"}}))
                      body (json/read-str body-json :key-fn keyword)]

                  (if (:error body)

                    ;; Something went wrong.
                    (throw (Exception. (str "FAILED TO GET ACCESS TOKEN: " body-json)))

                    ;; There was no error, so we should be able to find the access token
                    (let [access-token (:access_token body)
                          body-json (:body @(http/post "https://graph.facebook.com/v2.9/me?fields=email"
                                                       {:keepalive -1
                                                        :form-params {:access_token access-token}}))
                          body (json/read-str body-json :key-fn keyword)]

                      (swap! app-session assoc :facebook (merge body {:access-token access-token}))
                      (sessions/notify-session-update! app-session)


                      (-> (resp/response (-> (slurp (runtime-client-resource request "/facebook_auth_success.html"))
                                             (.replace "{{canonical-url}}" ^String (hiccup-util/escape-html (:app-origin session-state)))))
                          (resp/content-type "text/html")
                          (resp/status 200)))))))

            (catch Exception e
              (log/error e "Error in Facebook auth callback")
              (resp/redirect (str conf/static-root-url "/runtime-new/runtime/facebook_auth_error.html#" (codec/url-encode (.getMessage e))))))
          ))))

  (POST "/_/microsoft_auth_callback" req
    ;; Tokens reference: https://docs.microsoft.com/en-us/azure/active-directory/develop/active-directory-v2-tokens
    (let [redirect-uri (str conf/runtime-common-url "/_/microsoft_auth_callback")
          session-id (clojure.string/replace (or (-> req :params :state) "") #"G[^G]*$" "")
          app-session (sessions/load-session-by-id-without-authentication session-id)]

      (if-not app-session
        (resp/redirect (str conf/static-root-url "/runtime-new/runtime/microsoft_auth_error.html#" (codec/url-encode "SESSION_EXPIRED")))
        (if-let [error (-> req :params :error)]
          (resp/redirect (str conf/static-root-url "/runtime-new/runtime/microsoft_auth_error.html#" (codec/url-encode (str error ": " (-> req :params :error_description)))))

          (try
            (let [session-state (sessions/deref-db app-session)
                  request (merge req {:app-session app-session} (select-keys session-state [:app-id :app-info :environment]))
                  app (:content (get-app-from-request request))
                  microsoft-service (first (filter #(= (:source %) "/runtime/services/anvil/microsoft.yml") (:services app)))
                  provided-csrf-token (last (.split ^String (-> req :params :state) "G"))
                  load-id-token! (fn [id-token]
                                   (let [{:keys [kid alg]} (json/read-str (String. ^bytes (b64/decode (.getBytes ^String id-token))) :key-fn keyword)

                                         ; Only allow some algorithms, so we're not vulnerable to attacks that involve switching e.g. rs256 to hs256. See https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/
                                         allowed-algs #{:rs256}

                                         alg (some #{(keyword (.toLowerCase alg))} allowed-algs)

                                         tenant-id (when (get-in microsoft-service [:server_config :application_id])
                                                     (get-in microsoft-service [:server_config :tenant_id]))
                                         openid-config (json/read-str (:body @(http/get (str "https://login.microsoftonline.com/" (URLEncoder/encode (or tenant-id "common")) "/v2.0/.well-known/openid-configuration") {:keepalive -1})) :key-fn keyword)
                                         jwks (:keys (json/read-str (:body @(http/get (:jwks_uri openid-config) {:keepalive -1})) :key-fn keyword))
                                         jwk (first (filter #(= (:kid %) kid) jwks))

                                         public-key (buddy.core.keys/jwk->public-key jwk)

                                         verified-claims (buddy.sign.jwt/unsign id-token public-key {:alg alg})]

                                     (if (not= (:nonce verified-claims) (::microsoft-nonce session-state))
                                       (throw (Exception. "NONCE CHECK FAILED"))
                                       (swap! app-session assoc-in [:microsoft :id-token] verified-claims))))]

              ;; First, check the CSRF token matches the one we put in the session
              (if (not= provided-csrf-token (::microsoft-csrf-token session-state))

                ;; CSRF does not match. Fail.
                (throw (Exception. "CSRF CHECK FAILED"))

                ;; CSRF matches. Choose either the OpenID flow, or the auth code flow.
                (if-let [id-token (-> req :params :id_token)]
                  ; OpenID flow
                  (do
                    (load-id-token! id-token)
                    (sessions/notify-session-update! app-session))

                  ;; There was no ID token, so we must be using the full auth code flow.
                  (let [application-id (or (get-in microsoft-service [:server_config :application_id])
                                           (and (:custom? conf/microsoft-client-config) (:application-id conf/microsoft-client-config)))
                        application-secret (or (when-let [encrypted-app-secret (get-in microsoft-service [:server_config :application_secret_enc])]
                                                 (:value (secrets/get-global-app-secret-value (:app-info session-state) "microsoft-service/application-secret" encrypted-app-secret)))
                                               (get-in microsoft-service [:server_config :application_secret])
                                               (and (:custom? conf/microsoft-client-config) (:application-secret conf/microsoft-client-config)))
                        tenant-id (or (get-in microsoft-service [:server_config :tenant_id]) (and (:custom? conf/microsoft-client-config) (:tenant-id conf/microsoft-client-config)))
                        body-json (:body @(http/post (str "https://login.microsoftonline.com/" (URLEncoder/encode (util/or-str tenant-id "common")) "/oauth2/v2.0/token")
                                                     {:keepalive   -1
                                                      :form-params {:code          (-> req :params :code)
                                                                    :client_id     application-id
                                                                    :client_secret application-secret
                                                                    :redirect_uri  redirect-uri
                                                                    :grant_type    "authorization_code"}}))
                        body (json/read-str body-json :key-fn keyword)]

                    (if (:error body)

                      ;; Something went wrong.
                      (throw (Exception. (str "FAILED TO GET ACCESS TOKEN: " body-json)))

                      ;; There was no error, so we should be able to find the tokens
                      (do
                        (load-id-token! (:id_token body))
                        (swap! app-session update-in [:microsoft] merge {:refresh-token  (:refresh_token body)
                                                                         :access-token   (:access_token body)
                                                                         :application-id application-id})
                        (sessions/notify-session-update! app-session))))))

              (-> (resp/response (-> (slurp (runtime-client-resource req "/microsoft_auth_success.html"))
                                     (.replace "{{canonical-url}}" ^String (hiccup-util/escape-html (:app-origin session-state)))))
                  (resp/content-type "text/html")
                  (resp/status 200)))

            (catch Exception e
              (log/error e "Error in Microsoft auth callback")
              (resp/redirect (str conf/static-root-url "/runtime-new/runtime/microsoft_auth_error.html#" (codec/url-encode (or (.getMessage e) (.toString e)))))))))))

  (POST "/_/saml_auth_login" req
    (let [{relay-state :RelayState saml-response :SAMLResponse} (:params req)
          [_ session-id provided-csrf-token] (re-matches #"^(.*)G([^G]*)$" (codec/url-decode relay-state))]

      (log/info "SAML auth callback in session" (pr-str session-id) "with token" (pr-str provided-csrf-token))
      (let [app-session (sessions/load-session-by-id-without-authentication session-id)

            response-params {"{{canonical-url}}" (when app-session (hiccup-util/escape-html (:app-origin @app-session)))
                             "{{callback-fn}}"   "samlAuthErrorCallback"}]
        (when-not app-session
          (throw (Exception. (str "Session not found: " session-id))))
        (log/info "SAML CSRF token in session:" (pr-str (::saml-csrf-token @app-session)))

        (-> (resp/response
              (populate-template
                (runtime-client-resource req "/auth_result.html")
                (if-not (and app-session (= provided-csrf-token (::saml-csrf-token @app-session)))
                  (assoc response-params "{{args-json}}" (json/write-str {:message "Login failed: Invalid CSRF token"}))

                  ;; CSRF check passed
                  (let [request (merge req (select-keys @app-session [:app-id :app-info :environment]))
                        app (:content (get-app-from-request request))
                        saml-service (first (filter #(= (:source %) "/runtime/services/anvil/saml.yml") (:services app)))
                        settings (saml/get-settings (:server_config saml-service) (:app-info request))

                        saml-response (doto (SamlResponse. settings nil)
                                        (.loadXmlFromBase64 saml-response)
                                        (.setDestinationUrl (str conf/runtime-common-url "/_/saml_auth_login")))]

                    ;; Make sure we can't use this token again
                    (swap! app-session dissoc ::saml-csrf-token)

                    (if-not (.isValid saml-response)
                      (assoc response-params "{{args-json}}" (json/write-str {:message "Login failed: Invalid SAML response"}))

                      ;; SAML Response is valid
                      (let [attributes (into {} (.getAttributes saml-response))
                            name-id (.getNameId saml-response)
                            name-id-format (.getNameIdFormat saml-response)

                            email (first (or (get attributes (get-in saml-service [:server_config :email_attribute]))
                                             (and (= name-id-format Constants/NAMEID_EMAIL_ADDRESS)
                                                  [name-id])
                                             (get attributes "urn:oid:0.9.2342.19200300.100.1.3")
                                             (get attributes "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress")))]

                        (if-not email
                          (assoc response-params "{{args-json}}" (json/write-str {:message (str "Login failed: SAML response did not contain a valid email address. NameID format was \"" name-id-format "\". You may need to configure the Email Attribute setting in the SAML Service configuration.")}))
                          (do
                            ;; Useful reference for SAML attributes: https://edx.readthedocs.io/projects/edx-installing-configuring-and-running/en/named-release-dogwood.rc/configuration/tpa/tpa_SAML_IdP.html
                            (swap! app-session update-in [:saml] merge {:attributes (json/read-str (json/write-str attributes)) ;; This is silly, but gets rid of pesky ArrayLists that won't serialise.
                                                                        :email      email})
                            (sessions/notify-session-update! app-session)
                            (log/trace "Successful SAML Login:" (with-out-str (pprint attributes)))
                            (merge response-params {"{{callback-fn}}" "samlAuthSuccessCallback"
                                                    "{{args-json}}"   "null"})))))))))
            (resp/content-type "text/html")
            (resp/status 200)))))

  )
