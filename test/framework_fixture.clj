(ns framework-fixture
  (:require
    [framework.config.core :as config]
    [framework.db.core :as db-core]
    [framework.rbac.core :as rbac]
    [framework.route.core :as routes]
    [framework.session.core :as session-backend]
    [framework.sse.core :as sse]
    [framework.webserver.core :as ws]
    [piotr-yuxuan.closeable-map :refer [closeable-map]]
    [xiana.commons :refer [rename-key]]))

(defn ->system
  [app-cfg]
  (-> (config/config)
      (merge app-cfg)
      (rename-key :xiana/auth :auth)
      session-backend/init-backend
      db-core/docker-postgres!
      db-core/connect
      db-core/migrate!
      routes/reset
      rbac/init
      sse/init
      ws/start
      closeable-map))

(defn std-system-fixture
  [config f]
  (with-open [_ (->system config)]
    (f)))
