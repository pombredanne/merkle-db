(ns user
  "Custom repl customization for local development."
  (:require
    [blocks.core :as block]
    [blocks.store.file :refer [file-block-store]]
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.test.check.generators :as gen]
    [clojure.tools.namespace.repl :refer [refresh]]
    [com.stuartsierra.component :as component]
    (merkledag
      [core :as mdag]
      [link :as link]
      [node :as node])
    [merkledag.ref.file :as mrf]
    (merkle-db
      [bloom :as bloom]
      [connection :as conn]
      [db :as db]
      [generators :as mdgen]
      [key :as key]
      [partition :as part]
      [table :as table]
      [tablet :as tablet])
    [multihash.core :as multihash]
    [multihash.digest :as digest])
  (:import
    java.time.Instant
    java.time.format.DateTimeFormatter))


; TODO: replace this with reloaded repl
(def conn
  (conn/connect
    (mdag/init-store
      :store (file-block-store "var/db/blocks")
      :cache {:total-size-limit (* 32 1024)}
      :types {'inst
              {:reader #(Instant/parse ^String %)
               :writers {Instant #(.format DateTimeFormatter/ISO_INSTANT ^Instant %)}}})
    (doto (mrf/file-ref-tracker "var/db/refs.edn")
      (mrf/load-history!))))


(def db
  (try
    (conn/open-db conn "iris" {})
    (catch Exception ex
      (println "Failed to load iris database:" (pr-str ex)))))


(defn alter-db
  [f & args]
  (apply alter-var-root #'db f args))


#_
(defn bootstrap!
  []
  (conn/create-db! conn "iris" {})
  ; create table
  ; load data
  )
