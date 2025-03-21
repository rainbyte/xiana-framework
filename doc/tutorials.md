# Tutorials

- [Dependencies and configuration](#dependencies-and-configuration)
- [Database migration](#database-migration)
- [Database seed with data](#database-seed-with-data)
- [Interceptors typical use-case, and ordering](#interceptors-typical-use-case-and-ordering)
- [Defining new interceptors](#defining-new-interceptors)
    - [Interceptor example](#interceptor-example)
- [Router and controller interceptors](#router-and-controller-interceptors)
- [Providing default interceptors](#providing-default-interceptors)
- [Interceptor overriding](#interceptor-overriding)
- [Routes](#routes)
- [Action](#action)
- [Database-access](#database-access)
- [View](#view)
- [Side-effects](#side-effects)
- [Session management](#session-management)
- [Role based access and data ownership control](#role-based-access-and-data-ownership-control)
- [WebSockets](#websockets)
    - [WebSockets routing](#websockets-routing)
    - [Route matching](#route-matching)
- [Server-Sent Events (SSE)](#server-sent-events-sse)
- [Scheduler](#scheduler)

## Dependencies and configuration

Almost all components that you need on runtime should be reachable via the passed around state. To achieve this it
should be part of the :deps map in the state. Any other configuration what you need in runtime should be part of this
map too.

The system configuration and start-up with the chainable set-up:

```clojure
(defn ->system
  [app-cfg]
  (-> (config/config)
      (merge app-cfg)
      (rename-key :framework.app/auth :auth)
      (rename-key :framework.app/uploads :uploads)
      routes/reset
      session/init-backend
      sse/init
      db/start
      db/migrate!
      (scheduler/start actions/ping 10000)
      (scheduler/start actions/execute-scheduled-actions (* 60 1000))
      ws/start
      closeable-map))

(defn app-cfg
  [config]
  {:routes                  routes
   :router-interceptors     [(spa-index/wrap-default-spa-index "/re-frame")]
   :controller-interceptors (concat [(xiana-interceptors/muuntaja)
                                     cookies/interceptor
                                     xiana-interceptors/params
                                     (session/protected-interceptor "/api" "/login")
                                     xiana-interceptors/view
                                     xiana-interceptors/side-effect
                                     db/db-access]
                                    (:controller-interceptors config))})

(defn -main
  [& _args]
  (->system (app-cfg {})))
```

## Database migration

In migratus library there is an Achilles point:

It has no option to define separate migrations by profiles. Xiana
decorates [Migratus](https://github.com/yogthos/migratus), to handle this weakness.

You can run `lein migrate` with migratus parameters like: `create`, `destroy`, `up`, `down`, `init`, `reset`, `migrate`
, `rollback`. It will do the same as migratus, except one more thing: you can use `with profile` lein parameter to
define settings migratus should use. So instead of having only one migration folder you can define one for each of your
profiles.

```shell
lein with-profile +test migrate create default-users
```

Will create `up` and `down` SQL files in folder configured in `config/test/config.edn`, and

```shell
lein with-profile +test migrate migrate
```

will use it.

But without profile:

```shell
lein migrate migrate
```

migratus will use the migrations from a folder, what is configured in `config/dev/config.edn`.

## Database seed with data

With extending migration configuration with `seeds-dir` and `seeds-table-name` you can use

```shell
lein seed create
lein seed migrate
lein seed reset
lein seed destroy
```

commands. Every defined profile can have a different seeds directory to have different dataset for different
environments. If you're using this method to seed your data, keep your eye on the database structure is already updated
when the seeding is happens.

Example for configuration:

```clojure
:framework.db.storage/migration {:store                :database
                                 :migration-dir        "migrations"
                                 :seeds-dir            "dev_seeds"
                                 :migration-table-name "migrations"
                                 :seeds-table-name     "seeds"}
```

Example of using it from application start
```clojure
(-> (config/config app-cfg)
    ...
      db/connect
      db/migrate!
      seed/seed!
      ...)
```

## Interceptors typical use-case, and ordering

Typical use-case, and ordering looks like this:

```clojure
{:router-interceptors     [app/route-override?]
 :controller-interceptors [(interceptors/muuntaja)
                           interceptors/params
                           session/interceptor
                           interceptors/view
                           interceptors/db-access
                           rbac/interceptor]}
```

Which means:

1. executes app/route-override :enter function
2. executes app/route-override :leave function
3. The router injects :request-data, and decides what action should be executed
4. Muuntaja does the request's encoding
5. parameters injected via reitit
6. injecting session-data into the state
7. view does nothing on :enter
8. db-access does nothing on :enter
9. RBAC tests for permissions
10. execution of the given action
11. RBAC applies data ownership function
12. db-access executes the given query
13. rendering response map
14. updating session storage from state/session-data
15. Params do nothing on :leave
16. muuntaja converts the response body to the accepted format

## Defining new interceptors

    An interceptor is a map of three functions.
    :enter Runs while we are going down from the request to it's action, in the order of executors
    :leave Runs while we're going up from the action to the response.
    :error Executed when any error thrown while executing the two other functions

The provided function should have one parameter, the application state, and should return the state wrapped into the
xiana monad.

### Interceptor example

```clojure

{:enter (fn [state]
          (println "Enter: " state)
          (xiana/ok state))
 :leave (fn [state]
          (println "Leave: " state)
          (xiana/ok state))
 :error (fn [state]
          (println "Error: " state)
          (xiana/error (assoc state :response {:status 500 :body "Error occurred while printing out state"})))}
```

#### Router and controller interceptors

    The router and controller interceptors are executed in the exact same order (enter functions in order, leave 
    functions in reversed order), but not in the same place of the execution flow.

The handler function executes interceptors in this order

1. router interceptors :enter functions in order
2. router interceptors :leave functions in reversed order
3. routing, and matching
4. controller interceptors :enter functions in order
5. action
6. controller interceptors :leave functions in reversed order

In router interceptors, you are able to interfere with the routing mechanism. Controller interceptors can be interfered
with via route definition.

## Providing default interceptors

The router and controller interceptors definition is part of the application startup. The system's dependency map should
contain two sequence of interceptors like

```clojure
{:router-interceptors     [...]
 :controller-interceptors [...]}
```

## Interceptor overriding

On route definition you can interfere with the default controller interceptors. With the route definition you are able
to set up different controller interceptors other than the ones already defined with the app. There are three ways to do
it:

```clojure
... {:action       #(do something)
     :interceptors [...]}
```

will override all controller interceptors

```clojure
... {:action       #(do something)
     :interceptors {:around [...]}}
```

will extend the defaults around

```clojure
... {:action       #(do something)
     :interceptors {:inside [...]}}
```

will extend the defaults inside

```clojure
... {:action       #(do something)
     :interceptors {:inside [...]
                    :around [...]}}
```

will extend the defaults inside and around

```clojure
... {:action       #(do something)
     :interceptors {:except [...]}}
```

will skip the excepted interceptors from defaults

The execution flow will look like this

1. router interceptors :enters in order
2. router interceptors :leaves in reversed order
3. routing
4. around interceptors :enters in order
5. controller interceptors :enters in order
6. inside interceptors :enters in order
7. action
8. inside interceptors :leaves in reversed order
9. controller interceptors :leaves in reversed order
10. around interceptors :leaves in reversed order

All interceptors in :except will be skipped.

## Routes

Route definition is done via [reitit's routing](https://github.com/metosin/reitit) library. Route processing is done
with `framework.route.core` namespace. At route definition you can define.

- The [action](#action) that should be executed
- [Interceptor overriding](#interceptor-overriding)
- The required permission for [rbac](#role-based-access-and-data-ownership-control)
- [WebSocket](#websocket) action definition

If any extra parameter is provided here, it's injected into

```clojure
(-> state :request-data :match)
```

in routing step.

## Action

The action function in a single
[CRUD application](https://en.wikipedia.org/wiki/Create,_read,_update_and_delete#RESTful_APIs) is for defining a
[view](#view), a [database-query](#database-access) and optionally a [side-effect](#side-effects) function which will be
executed in the following interceptor steps.

```clojure
(defn action
  [state]
  (xiana/ok
    (assoc state :view view/success
                 :side-effect behaviour/update-sessions-and-db!
                 :query model/fetch-query)))
```

## Database-access

The `database.core`'s interceptor extracts the datasource from the provided state parameter and the :query.

The query should be in [honey SQL](https://github.com/nilenso/honeysql-postgres) format, it will be sql-formatted on
execution:

```clojure
(defn fetch-query
  [state]
  (let [login (-> state :request :body-params :login)]
    (-> (select :*)
        (from :users)
        (where [:and
                :is_active
                [:or
                 [:= :email login]
                 [:= :username login]]]))))
```

The execution always has `{:return-keys true}` parameter and the result goes into

```clojure
(-> state :response-data :db-data)
```

without any transformation.

## View

A view is a function to prepare the final response and saving it into the state based on whatever happened before.

```clojure
(defn success
  [state]
  (let [{:users/keys [id]} (-> state :response-data :db-data first)]
    (xiana/ok
      (assoc state :response {:status  200
                              :headers {"Content-type" "Application/json"}
                              :body    {:view-type "login"
                                        :data      {:login   "succeed"
                                                    :user-id id}}}))))
```

## Side-effects

Conventionally, side-effects interceptor is placed after [action](#action) and [database-access](#database-access), just
right before [view](#view). At this point, we already have the result of database execution, so we are able to do some
extra refinements, like sending notifications, updating the application state, filtering or mapping the result and so
on.

Adding to the previous examples:

```clojure
(defn update-sessions-and-db!
  "Creates and adds a new session to the server's store for the user that wants to sign-in.
   Avoids duplication by firstly removing the session that is related to this user (if it exists).
   After the session addition, it updates the user's last-login value in the database."
  [state]
  (if (valid-credentials? state)
    (let [new-session-id (str (UUID/randomUUID))
          session-backend (-> state :deps :session-backend)
          {:users/keys [id] :as user} (-> state :response-data :db-data first)]
      (remove-from-session-store! session-backend id)
      (xiana-sessions/add! session-backend new-session-id user)
      (update-user-last-login! state id)
      (xiana/ok
        (assoc-in state [:response :headers "Session-id"] new-session-id)))
    (xiana/error (c/not-allowed state))))
```

## Session management

Session interceptor interchanges session data between the session-backend and the app state.

On `:enter` it loads the session by its session-id, into `(-> state :session-data)`

The session-id can be provided either in headers, cookies, or as query-param. When session-id is found nowhere or is an
invalid UUID, or the session is not stored in the storage, then the response will be:

```clojure
{:status 401
 :body   "Invalid or missing session"}
```

On the `:leave` branch, updates session storage with the data from `(-> state :session-data)`

## Role based access and data ownership control

To get the benefits of [tiny RBAC](https://github.com/Flexiana/tiny-rbac) library you need to provide the resource and
the action for your endpoint in [router](#routes) definition:

```clojure
[["/api"
  ["/image" {:delete {:action     delete-action
                      :permission :image/delete}}]]]
```

and add your role-set into your app's [dependencies](#dependencies-and-configuration):

```clojure
(defn ->system
  [app-cfg]
  (-> (config/config)
      (merge app-cfg)
      framework.rbac.core/init
      ws/start))
```

On `:enter`, the interceptor performs the permission check. It determines if the action allowed for the user found
in `(-> state :session-data :user)`. If access to the resource/action isn't permitted, then the response is:

```clojure
{:status 403
 :body   "Forbidden"}
```

If a permission is found, then it goes into `(-> state :request-data :user-permissions)` as a parameter for data
ownership processing.

On `:leave`, executes the restriction function found in `(-> state :request-data :restriction-fn)`. The `restriction-fn`
should look like this:

```clojure
(defn restriction-fn
  [state]
  (let [user-permissions (get-in state [:request-data :user-permissions])]
    (cond
      (user-permissions :image/all) (xiana/ok state)
      (user-permissions :image/own) (xiana/ok
                                      (let [session-id (get-in state [:request :headers "session-id"])
                                            session-backend (-> state :deps :session-backend)
                                            user-id (:users/id (session/fetch session-backend session-id))]
                                        (update state :query sql/merge-where [:= :owner.id user-id]))))))
```

The rbac interceptor must be placed between the [action](#action) and the [db-access](#database-access) interceptors in
the [interceptor chain](#interceptors-typical-use-case-and-ordering).

## WebSockets

To use an endpoint to serve a WebSockets connection, you can define it on route-definition alongside the restfull
action:

```clojure
(def routes
  [[...]
   ["/ws" {:ws-action websocket/echo
           :action    restfull/hello}]])
```

In `:ws-action` function you can provide the reactive functions in `(-> state :response-data :channel)`

```clojure
(:require
  ...
  [framework.websockets.core :refer [router string->]]
  ...)

(defonce channels (atom {}))

(def routing
  (partial router routes string->))

(defn chat-action
  [state]
  (xiana/ok
    (assoc-in state [:response-data :channel]
              {:on-receive (fn [ch msg]
                             (routing (update state :request-data
                                              merge {:ch         ch
                                                     :income-msg msg
                                                     :fallback   views/fallback
                                                     :channels   channels})))
               :on-open    (fn [ch]
                             (routing (update state :request-data
                                              merge {:ch         ch
                                                     :channels   channels
                                                     :income-msg "/welcome"})))
               :on-ping    (fn [ch data])
               :on-close   (fn [ch status] (swap! channels dissoc ch))
               :init       (fn [ch])})))
```

The creation of the actual channel happens in framework's [handler](conventions.md#handler). All provided reactive
functions have the entire [state](conventions.md#state) to work with.

### WebSockets routing

`framework.websockets.core` offers a router function, which supports Xiana concepts. You can define a reitit route and
use it inside WebSockets reactive functions. With Xiana [monad](conventions.md#monads), [state](conventions.md#state)
and support of [interceptors](conventions.md#interceptors), with [interceptor override](#interceptor-overriding). You
can define a [fallback function](#websockets), to handle missing actions.

```clojure
(def routes
  (r/router [["/login" {:action       behave/login
                        :interceptors {:inside [interceptors/side-effect
                                                interceptors/db-access]}
                        :hide         true}]]              ;; framework.websockets.core/router will not log the message 
            {:data {:default-interceptors [(interceptors/message "Incoming message...")]}}))
```

### Route matching

For route matching Xiana provides a couple of modes:

- extract from string

  The first word of given message as actionable symbol

- from JSON

  The given message parsed as JSON, and `:action` is the actionable symbol

- from EDN

  The given message parsed as EDN, and `:action` is the actionable symbol

- Probe

  It tries to decode the message as JSON, then as EDN, then as string.

You can also define your own matching, and use it as a parameter to `framework.websockets.core/router`

## Server-Sent Events (SSE)

Xiana contains a simple SSE solution over [http-kit](https://github.com/http-kit/http-kit) server's `Channel`
protocol.

Initialization is done by calling `framework.sse.core/init`. Clients can subscribe by routing
to `framework.sse.core/sse-action`. Messages are sent with `framework.sse.core/put!` function.

```clojure
(ns app.core
  (:require
    [framework.config.core :as config]
    [framework.sse.core :as sse]
    [framework.route.core :as route]
    [framework.webserver.core :as ws]
    [xiana.core :as xiana]))

(def routes
  [["/sse" {:action sse/sse-action}]
   ["/broadcast" {:action (fn [state]
                            (sse/put! state {:message "This is not a drill!"})
                            (xiana/ok state))}]])

(defn ->system
  [app-cfg]
  (-> (config/config)
      (merge app-cfg)
      sse/init
      ws/start))

(def app-cfg
  {:routes routes})

(defn -main
  [& _args]
  (->system app-cfg))
```

## Scheduler

To repeatedly execute a function, you can use the `framework.scheduler.core/start` function. Below is an implementation
of SSE ping:

```clojure
(ns app.core
  (:require
    [framework.scheduler.core :as scheduler]
    [clojure.core.async :as async]))

(defn ping [deps]
  (let [channel (get-in deps [:events-channel :channel])]
    (async/>!! channel {:type      :ping
                        :id        (str (UUID/randomUUID))
                        :timestamp (.getTime (Date.))})))

(defn ->system
  [app-cfg]
  (-> (config/config)
      (merge app-cfg)
      ...
      sse/init
      (scheduler/start ping 10000)
      ...))
```