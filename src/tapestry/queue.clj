(ns tapestry.queue
  "Portable blocking queue over `clojure.core.async`.

  Replaces the JVM `java.util.concurrent.locks` implementation. A queue is
  backed by a core.async channel — unbuffered for synchronous (rendezvous)
  queues, buffered for bounded and unbounded ones — so blocking and timeouts
  delegate to the channel. An `items` mirror atom provides the point-in-time
  snapshot; it is approximate under concurrency, which matches the documented
  'inspection and debugging' contract."
  (:require [clojure.core.async :as a])
  (:import [clojure.lang PersistentQueue]))

(set! *warn-on-reflection* true)

(deftype ^:no-doc Queue
    [chan          ;; core.async channel carrying the items
     items*        ;; atom: PersistentQueue mirroring in-flight items (snapshot)
     closed*       ;; atom: boolean, set by close!
     close-promise ;; clojure.core/promise: delivered on close!
     sync?         ;; boolean: rendezvous (unbuffered) mode
     capacity])

(defn queue
  "Create a new queue with an optional `capacity`.

  With no capacity the queue is synchronous (rendezvous): a put blocks until a
  matching take. A numeric `capacity` makes a bounded queue. `:unbounded`
  allows up to `Long/MAX_VALUE` items."
  ([] (queue nil))
  ([capacity]
   (let [sync?      (nil? capacity)
         unbounded? (= :unbounded capacity)
         cap        (long (cond sync?      0
                                unbounded? Long/MAX_VALUE
                                :else      capacity))
         ch         (if sync? (a/chan) (a/chan cap))]
     (Queue. ch (atom PersistentQueue/EMPTY) (atom false) (promise) sync? capacity))))

(defn queue?
  "Return whether the provided `obj` is a queue."
  [obj]
  (instance? Queue obj))

(defn closed?
  "Return whether the provided queue has been closed."
  [^Queue q]
  @(.closed* q))

(defn await-close
  "Block until `q` is closed."
  [^Queue q]
  (when-not @(.closed* q)
    @(.close-promise q))
  true)

(defn- mirror-push! [^Queue q item]
  (swap! (.items* q) #(conj % item)))

(defn- mirror-pop! [^Queue q]
  (swap! (.items* q) pop))

(defn close!
  "Close the `q`. Subsequent puts return `false`; pending and future takes
  drain remaining items, then return `nil`. Returns `true`."
  [^Queue q]
  (when-not @(.closed* q)
    (reset! (.closed* q) true)
    (a/close! (.chan q))
    (deliver (.close-promise q) true))
  true)

(defn put!
  "Place `item` in `q`, blocking until space is available.

  Returns `true` if the item was queued, or `false` if the queue is closed."
  [^Queue q item]
  (if @(.closed* q)
    false
    (do (mirror-push! q item)
        (if (a/>!! (.chan q) item)
          true
          (do (mirror-pop! q) false)))))

(defn try-put!
  "Place `item` in `q`, waiting at most `timeout-ms` milliseconds.

  Returns `true` if accepted, `false` if the queue is closed (or closes before
  acceptance), or `timeout-val` if the timeout elapses. `timeout-val` defaults
  to `false`. A `timeout-ms` of `0` probes without waiting."
  ([^Queue q item] (try-put! q item 0 false))
  ([^Queue q item timeout-ms] (try-put! q item timeout-ms false))
  ([^Queue q item timeout-ms timeout-val]
   (if @(.closed* q)
     false
     (let [ch (.chan q)]
       (if (pos? (long timeout-ms))
         (do (mirror-push! q item)
             (let [[val port] (a/alts!! [[ch item] (a/timeout (long timeout-ms))])]
               (cond
                 (identical? port ch) (if val true (do (mirror-pop! q) false))
                 :else                 (do (mirror-pop! q) timeout-val))))
         ;; timeout-ms <= 0: non-blocking probe.
         (let [r (a/offer! ch item)]
           (cond
             (true? r)  (do (mirror-push! q item) true)
             (false? r) false
             :else      timeout-val)))))))

(defn take!
  "Take an item from `q`, blocking until one is available.

  Returns `nil` if the queue is closed and drained."
  [^Queue q]
  (let [v (a/<!! (.chan q))]
    (when (some? v) (mirror-pop! q))
    v))

(defn try-take!
  "Try to take from `q`, waiting at most `timeout-ms` before returning
  `timeout-val` (default `nil`). Returns `nil` if `q` is closed and drained.
  A `timeout-ms` of `0` probes without waiting."
  ([^Queue q] (try-take! q 0 nil))
  ([^Queue q timeout-ms] (try-take! q timeout-ms nil))
  ([^Queue q timeout-ms timeout-val]
   (let [ch (.chan q)]
     (if (pos? (long timeout-ms))
       (let [[val port] (a/alts!! [ch (a/timeout (long timeout-ms))])]
         (cond
           (identical? port ch) (do (when (some? val) (mirror-pop! q)) val)
           :else                 timeout-val))
       ;; timeout-ms <= 0: non-blocking probe.
       (let [v (a/poll! ch)]
         (when (some? v) (mirror-pop! q))
         v)))))

(defn items
  "Return a vector containing a point-in-time snapshot of the items currently
  in `q`. Approximate under concurrency; intended for inspection and debugging."
  [^Queue q]
  (vec @(.items* q)))

(defmethod print-method Queue [^Queue q ^java.io.Writer w]
  (.write w
          (str "#queue"
               {:capacity (if (.sync? q) :sync (.capacity q))
                :items    (items q)
                :closed?  (closed? q)})))
