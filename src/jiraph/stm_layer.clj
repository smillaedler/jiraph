(ns jiraph.stm-layer
  (:refer-clojure :exclude [meta])
  (:use [jiraph.layer :only [Enumerate Basic Layer Optimized ChangeLog get-revisions close]]
        [jiraph.graph :only [with-action get-node]]
        [jiraph.utils :only [meta-id meta-id? base-id]]
        [retro.core :only [WrappedTransactional Revisioned OrderedRevisions
                           max-revision at-revision current-revision]]
        [useful.fn :only [given fix]]
        [useful.utils :only [returning or-min]]
        [useful.datatypes :only [assoc-record]])
  (:import (java.io FileNotFoundException)))

(comment
  The STM layer stores a single ref, pointing to a series of whole-layer
  snapshots over time, one per committed revision. Each snapshot contains
  a :nodes section and a :meta section - the meta is managed entirely by
  jiraph.graph. Note that while metadata is revisioned, it does not keep a
  changelog like nodes do, so you can't meaningfully read metadata from a
  revision in the future (which you can do for nodes).

  The layer also tracks a current revision, to allow any snapshot to be
  viewed as if it were the full graph.

  So a sample STM layer might look like the following.

  {:revision 2
   :store (ref {0 {}
                1 {:meta {"some-key" "some-value"}
                   :nodes {"profile-4" {:name "Rita" :edges {"profile-9" {:rel :child}}}}}
                2 {:meta {"some-key" "other-value"}
                   :nodes {"profile-4" {:name "Rita" :edges {"profile-9" {:rel :child}}
                                        :age 39}
                           "profile-9" {:name "William"}}}})})

(def empty-store (sorted-map-by > 0 {}))

(defn storage-area [k]
  (if (vector? k), :meta, :nodes))

(defn storage-name [k]
  (fix k vector? first))

(defn current-rev [layer]
  (let [store @(:store layer)]
    (ffirst (if-let [rev (:revision layer)]
              (subseq store >= rev)
              store))))

(defn now [layer]
  (-> layer :store deref (get (current-rev layer))))

(def ^{:private true} nodes (comp :nodes now))
(def ^{:private true} meta  (comp :meta now))

(defrecord STMLayer [store revision filename]
  Object
  (toString [this]
    (pr-str this))

  Enumerate
  (node-id-seq [this]
    (-> this nodes keys))
  (node-seq [this]
    (-> this nodes seq))

  Basic
  (get-node [this k not-found]
    (if (meta-id? k)
      (-> this meta (get (base-id k) not-found))
      (let [n (-> this nodes (get k not-found))]
        (if-not (identical? n not-found)
          n
          (let [touched-revisions (get-revisions (at-revision this nil) k)
                most-recent (or (first (if revision
                                         (drop-while #(> % revision) touched-revisions)
                                         touched-revisions))
                                0)]
            (-> @store (get-in [most-recent :nodes k] not-found)))))))
  (assoc-node [this k v]
    (with-action [layer this] {:old (get-node this k) :new v}
      (alter store
             assoc-in [(current-rev layer) (storage-area k) (storage-name k)] v)))
  (dissoc-node [this k]
    (with-action [layer this] {:old (get-node this k) :new nil}
      (alter store
             update-in [(current-rev layer) (storage-area k)] dissoc (storage-name k))))

  Revisioned
  (at-revision [this rev]
    (assoc-record this :revision rev))
  (current-revision [this]
    revision)

  OrderedRevisions
  (max-revision [this]
    (-> @store ))
  (touch [this]
    nil) ;; hopefully that works?

  WrappedTransactional
  (txn-wrap [_ f]
    (fn []
      (dosync (f))))

  Layer
  (open [this]
    (when filename
      (dosync
       (try
         (doto this
           (-> :store (ref-set (read-string (slurp filename)))))
         (catch FileNotFoundException e this)))))
  (close [this]
    (when filename
      (spit filename @store)))
  (fsync [this]
    (with-action [_ this]
      (close this)))
  (truncate [this]
    (with-action [_ this]
      (ref-set store empty-store)))
  (optimize [this] nil))

(defn make
  ([] (make nil))
  ([filename] (STMLayer. (ref empty-store) nil filename)))
