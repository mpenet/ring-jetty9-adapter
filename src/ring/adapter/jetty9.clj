(ns ring.adapter.jetty9
  "Adapter for the Jetty 9 server, with websocket support.
Derived from ring.adapter.jetty"
  (:require
   [ring.util.servlet :as servlet]
   [clojure.string :as string]
   [clojure.core.async :as async])
  (:import
   (org.eclipse.jetty.server
    Handler
    Server
    Request
    ServerConnector
    HttpConfiguration
    HttpConnectionFactory
    SslConnectionFactory
    ConnectionFactory)
   (org.eclipse.jetty.server.handler
    HandlerCollection
    AbstractHandler
    ContextHandler
    HandlerList)
   (org.eclipse.jetty.util.thread
    QueuedThreadPool
    ScheduledExecutorScheduler)
   (org.eclipse.jetty.util.ssl SslContextFactory)
   (org.eclipse.jetty.websocket.server WebSocketHandler)
   (org.eclipse.jetty.websocket.servlet
    WebSocketServletFactory
    WebSocketCreator
    ServletUpgradeRequest
    ServletUpgradeResponse)
   (javax.servlet.http
    HttpServletRequest
    HttpServletResponse)
   (org.eclipse.jetty.websocket.api
    WebSocketAdapter
    Session
    UpgradeRequest)
   (java.nio ByteBuffer)
   (clojure.lang IFn)))

(defprotocol WebSocketProtocol
  (send! [this msg])
  (close! [this])
  (remote-addr [this])
  (idle-timeout! [this ms]))

(defprotocol WebSocketSend
  (-send! [x ws] "How to encode content sent to the WebSocket clients"))

(extend-protocol WebSocketSend

  (Class/forName "[B")
  (-send! [ba ws]
    (-send! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-send! [bb ws]
    (-> ws .getRemote (.sendBytes ^ByteBuffer bb)))

  String
  (-send! [s ws]
    (-> ws .getRemote (.sendString ^String s)))

  IFn
  (-send! [f ws]
    (-> ws .getRemote f))

  Object
  (-send! [this ws]
    (-> ws .getRemote (.sendString (str this))))

  ;; "nil" could PING?
  ;; nil
  ;; (-send! [this ws] ()

  )

(extend-protocol WebSocketProtocol
  WebSocketAdapter
  (send! [this msg]
    (-send! msg this))
  (close! [this]
    (.. this (getSession) (close)))
  (remote-addr [this]
    (.. this (getSession) (getRemoteAddress)))
  (idle-timeout! [this ms]
    (.. this (getSession) (setIdleTimeout ^long ms))))

(defn- do-nothing [& args])

(defrecord WebSocketBinaryFrame [payload offset len])

(defn close-chans!
  [& chs]
  (doseq [ch chs]
    (async/close! ch)))

(use 'clojure.tools.logging)

(defn- proxy-ws-adapter
  [handler]
  (let [recv-ch (async/chan)
        send-ch (async/chan)
        ctrl-ch (async/chan)]
    (proxy [WebSocketAdapter] []
      (onWebSocketConnect [^Session session]
        (proxy-super onWebSocketConnect session)
        (async/put! ctrl-ch [::connected this])
        (handler {:recv-ch recv-ch :send-ch send-ch :ctrl-ch ctrl-ch :ws this}))
      (onWebSocketError [^Throwable e]
        (async/put! ctrl-ch [:error this e])
        (close-chans! recv-ch send-ch ctrl-ch))
      (onWebSocketText [^String message]
        (async/put! recv-ch message))
      (onWebSocketClose [statusCode ^String reason]
        (proxy-super onWebSocketClose statusCode reason)
        (async/put! ctrl-ch [::close this reason])
        (close-chans! recv-ch send-ch ctrl-ch))
      (onWebSocketBinary [^bytes payload offset len]
        (async/put! recv-ch (WebSocketBinaryFrame. payload offset len))))))

(defn- reify-ws-creator
  [handler]
  (reify WebSocketCreator
    (createWebSocket [this _ _]
      (proxy-ws-adapter handler))))

(defprotocol RequestMapDecoder
  (build-request-map [r]))

(extend-protocol RequestMapDecoder
  HttpServletRequest
  (build-request-map [request]
    (servlet/build-request-map request))

  UpgradeRequest
  (build-request-map [request]
    {:uri (.getRequestURI request)
     :query-string (.getQueryString request)
     :origin (.getOrigin request)
     :host (.getHost request)
     :request-method (keyword (.toLowerCase (.getMethod request)))
     :headers (reduce(fn [m [k v]]
                 (assoc m (string/lower-case k) (string/join "," v)))
               {}
               (.getHeaders request))}))

(defn- proxy-ws-handler
  "Returns a Jetty websocket handler"
  [handlers {:as options
             :keys [ws-max-idle-time]
             :or {ws-max-idle-time 500000}}]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (-> (.getPolicy factory)
          (.setIdleTimeout ws-max-idle-time))
      (.setCreator factory (reify-ws-creator handlers)))))

(defn- proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
          (.setHandled base-request true))))))

(defn- http-config
  [{:as options
    :keys [ssl-port secure-scheme output-buffer-size request-header-size
           response-header-size send-server-version? send-date-header?
           header-cache-size]
    :or {ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 512}}]
  "Creates jetty http configurator"
  (doto (HttpConfiguration.)
    (.setSecureScheme secure-scheme)
    (.setSecurePort ssl-port)
    (.setOutputBufferSize output-buffer-size)
    (.setRequestHeaderSize request-header-size)
    (.setResponseHeaderSize response-header-size)
    (.setSendServerVersion send-server-version?)
    (.setSendDateHeader send-date-header?)
    (.setHeaderCacheSize header-cache-size)))

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [{:as options
    :keys [keystore keystore-type key-password client-auth
           truststore trust-password truststore-type]}]
  (let [context (SslContextFactory.)]
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context ^java.security.KeyStore keystore))
    (.setKeyStorePassword context key-password)
    (when keystore-type
      (.setKeyStoreType context keystore-type))
    (when truststore
      (.setTrustStore context ^java.security.KeyStore truststore))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (when truststore-type
      (.setTrustStoreType context truststore-type))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- create-server
  "Construct a Jetty Server instance."
  [{:as options
    :keys [port max-threads daemon? max-idle-time host ssl? ssl-port]
    :or {port 80
         max-threads 50
         daemon? false
         max-idle-time 200000
         ssl? false}}]
  (let [pool (doto (QueuedThreadPool. (int max-threads))
               (.setDaemon daemon?))
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.)))

        http-configuration (http-config options)
        http-connector (doto (ServerConnector.
                              ^Server server
                              (into-array ConnectionFactory [(HttpConnectionFactory. http-configuration)]))
                         (.setPort port)
                         (.setHost host)
                         (.setIdleTimeout max-idle-time))

        https-connector (when (or ssl? ssl-port)
                          (doto (ServerConnector.
                                 ^Server server
                                 (ssl-context-factory options)
                                 (into-array ConnectionFactory [(HttpConnectionFactory. http-configuration)]))
                            (.setPort ssl-port)
                            (.setHost host)
                            (.setIdleTimeout max-idle-time)))

        connectors (if https-connector
                     [http-connector https-connector]
                     [http-connector])
        connectors (into-array connectors)]
    (.setConnectors server connectors)
    server))

(defn ^Server run-jetty
  "Start a Jetty webserver to serve the given handler according to the
supplied options:

:port - the port to listen on (defaults to 80)
:host - the hostname to listen on
:join? - blocks the thread until server ends (defaults to true)
:daemon? - use daemon threads (defaults to false)
:ssl? - allow connections over HTTPS
:ssl-port - the SSL port to listen on (defaults to 443, implies :ssl?)
:keystore - the keystore to use for SSL connections
:keystore-type - the format of keystore
:key-password - the password to the keystore
:truststore - a truststore to use for SSL connections
:truststore-type - the format of trust store
:trust-password - the password to the truststore
:max-threads - the maximum number of threads to use (default 50)
:max-idle-time  - the maximum idle time in milliseconds for a connection (default 200000)
:ws-max-idle-time  - the maximum idle time in milliseconds for a websocket connection (default 500000)
:client-auth - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
:websockets - a map from context path to a map of handler fns:

 {\"/context\" {:on-connect #(create-fn %)                ; ^Session ws-session
                :on-text   #(text-fn % %2 %3 %4)         ; ^Session ws-session message
                :on-bytes  #(binary-fn % %2 %3 %4 %5 %6) ; ^Session ws-session payload offset len
                :on-close  #(close-fn % %2 %3 %4)        ; ^Session ws-session statusCode reason
                :on-error  #(error-fn % %2 %3)}}         ; ^Session ws-session e"
  [handler {:as options
            :keys [max-threads websockets configurator join?]
            :or {max-threads 50
                 join? true}}]
  (let [^Server s (create-server options)
        ^QueuedThreadPool p (QueuedThreadPool. (int max-threads))
        ring-app-handler (proxy-handler handler)
        ws-handlers (map (fn [[context-path handler]]
                           (doto (ContextHandler.)
                             (.setContextPath context-path)
                             (.setHandler (proxy-ws-handler handler options))))
                         websockets)
        contexts (doto (HandlerList.)
                   (.setHandlers
                    (into-array Handler (reverse (conj ws-handlers ring-app-handler)))))]
    (.setHandler s contexts)
    (when-let [c configurator]
      (c s))
    (.start s)
    (when join?
      (.join s))
    s))


(comment
  (run-jetty nil {:port 8013
                 :websockets {"/api/entries/realtime/"
                              (fn [{:keys [recv-ch send-ch ctrl-ch ws]
                                    :as opts}]
                                (async/go
                                  (loop []
                                    (let [x (async/<! ctrl-ch)]
                                      (info :ctrl x ctrl-ch)
                                      (recur))))
                                (async/go
                                  (loop []
                                    (let [x (async/<! recv-ch)]
                                      (info :recv x recv-ch)
                                      (recur))))

                                (async/go
                                  (loop []
                                    (let [x (async/<! send-ch)]
                                      (info :send x send-ch)
                                      (recur))))

                                ;; (close! ws)
                                )}}))
