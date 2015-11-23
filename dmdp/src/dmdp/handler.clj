(ns dmdp.handler
  (:require [compojure.core :refer [defroutes routes wrap-routes]]
            [dmdp.layout :refer [error-page]]
            [dmdp.routes.home :refer [private-routes public-routes]]
            [dmdp.middleware :as middleware]
            ;[dmdp.db.core :as db]
            [compojure.route :as route]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
            [selmer.parser :as parser]
            [environ.core :refer [env]]))

(defn init
  "init will be called once when
   app is deployed as a servlet on
   an app server such as Tomcat
   put any initialization code here"
  []

  (timbre/merge-config!
    {:level     (if (env :dev) :trace :info)
     :appenders {:rotor (rotor/rotor-appender
                          {:path "dmdp.log"
                           :max-size (* 512 1024)
                           :backlog 10})}})

  (if (env :dev) (parser/cache-off!))
  ;(db/connect!)
  (timbre/info (str
                 "\n-=[dmdp started successfully"
                 (when (env :dev) " using the development profile")
                 "]=-")))

(defn destroy
  "destroy will be called when your application
   shuts down, put any clean up code here"
  []
  (timbre/info "dmdp is shutting down...")
;  (db/disconnect!)
  (timbre/info "shutdown complete!"))

#_(def app
  (-> (routes
        (-> private-routes
            (wrap-routes middleware/wrap-csrf)
            (wrap-routes middleware/wrap-restricted))
        public-routes)
        middleware/wrap-base))

(def app-routes
  (routes
   (wrap-routes #'public-routes middleware/wrap-csrf)
   (wrap-routes #'private-routes middleware/wrap-csrf)
   (route/not-found
    (:body
     (error-page {:status 404
                  :title "page not found"})))))

(def app (middleware/wrap-base #'app-routes))
