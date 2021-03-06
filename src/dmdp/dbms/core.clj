(ns dmdp.dbms.core
    (:require [taoensso.nippy :as nippy]
              [dmdp.dbms.join :refer [natural-join cross-join]])
    (:use     [dmdp.dbms.utils]
              [dmdp.dbms.forms]))


; ---- BYTES LENGTH FOR DIFFERENT DATA TYPES
(def INTEGER-BYTES-LENGTH 9)
(def PAGE-DESCRIPTOR-BYTES-LENGTH 36)
; ------------------------------------------
; ---- BIT FLAG CONSTANTS
(def FLAG-PRIMARY-KEY 1)
; ------------------------------------------
; ---- OTHER CONSTANTS
(def PAGES-PER-TABLE 67)
(def PAGE-SIZE (* 4 1024))
(def HEADER-SIZE (* 64 1024))
;-------------------------------------------

(defn read-header-size
  [db-title]
  (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
     (.seek raf 0)
     (let [buf (byte-array INTEGER-BYTES-LENGTH)
           _ (.read raf buf)]
       (nippy/thaw buf))))

(defn read-header
   [db-title]
   (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
     (.seek raf INTEGER-BYTES-LENGTH)
     (let [buf (byte-array (read-header-size db-title))
           _ (.read raf buf)]
       (nippy/thaw buf))))

(defn get-header
  [db-title]
  (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
  (.seek raf 0)
  (let [length (.length raf)]
  (if (< length INTEGER-BYTES-LENGTH)
   (generate-header)
   (read-header db-title)))))

(defn read-page
  [db-title offset]
  (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
    (let [buf-page-size (byte-array INTEGER-BYTES-LENGTH)
          page-size (do
            (.seek raf offset)
            (.read raf buf-page-size)
            (nippy/thaw buf-page-size))
          buf-page (byte-array page-size)
          page (do
            (.read raf buf-page)
            (nippy/thaw buf-page))]
      page)))

(defn read-page-offset
  [db-title table-name page-id]
  (let [header (get-header db-title)
        page-descriptor (->> header
                       :table-descriptors
                       (filter #(= table-name (:table-name %)))
                       first
                       :page-descriptors
                       (filter #(= page-id (:page-id %)))
                       first)
        offset (:offset page-descriptor)]
    offset))



(defn get-attributes
  [db-title table-name]
  (let [table-descriptors (:table-descriptors (get-header db-title))
        attributes (:attributes (first (filter #(= table-name (:table-name %)) table-descriptors)))]
        attributes))

(defn calc-record-hash
  [db-title table-name record]
  (loop [i 0
         hash-acc 0
         attributes (get-attributes db-title table-name)]
         (if (< i (count record))
          (recur (inc i) (let [current-attribute (nth attributes i)
                               current-attribute-flags (:flags current-attribute)
                               current-field (nth record i)
                               current-field-value-hash (hash (:value current-field))]
                            (if (= (bit-and current-attribute-flags FLAG-PRIMARY-KEY) 1)
                                (+ hash-acc current-field-value-hash) hash-acc)) attributes)
          (let [result (Math/abs (mod (Math/abs hash-acc) PAGES-PER-TABLE))]
            result))))

(defn write-header
  [db-title header]
  (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
   (.seek raf 0)
   (let [data (nippy/freeze header)
         size (count data)]
   (.write raf (nippy/freeze size))
   (.write raf data)
   (if (<= (.length raf) HEADER-SIZE)
      (.setLength raf HEADER-SIZE)))))

(defn write-page
  [db-title offset page]
  (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
    (.seek raf offset)
    (let [data (nippy/freeze page)
          size (count data)
          ; recalc file length
          length (.length raf)
          diff-length (- length HEADER-SIZE)
          length-remainder (rem diff-length PAGE-SIZE)
          new-file-length (+ (- PAGE-SIZE length-remainder) diff-length HEADER-SIZE)]
          (.write raf (nippy/freeze size))
          (.write raf data)
          (.setLength raf new-file-length))))

(declare read-table-descriptor)

(defn get-table-attributes
  [db-title table-name]
  (map :id (:attributes (read-table-descriptor db-title table-name))))


(defn add-table
  [db-title table-name attributes]
   ; TODO: recalc table-id
   ; TODO: shift File
   ; TODO: unique table names
     (let [header (get-header db-title)
           table-descriptor (generate-table-descriptor (count (header :table-descriptors)) table-name attributes)]
      (write-header db-title (generate-header (conj (header :table-descriptors) table-descriptor)))))

(defn get-page-descriptors
  [db-title table-name]
  (let [header (get-header db-title)]
    (->> header
         :table-descriptors
         (filter #(= table-name (:table-name %)))
         first
         :page-descriptors)))

(defn read-table-descriptors
 [db-title]
 (let [header (get-header db-title)
       table-descriptors (:table-descriptors header)]
   table-descriptors))

(defn read-table-descriptor
  [db-title table-name]
  (->> (read-table-descriptors db-title)
       (filter #(= table-name (:table-name %)))
       first))

       (defn table-name->table-id
         [db-title table-name]
         (:table-id (read-table-descriptor db-title table-name)))

(defn get-table-id
  [db-title table-name]
  (let [header (get-header db-title)]
    (->> header
         :table-descriptors
         (filter #(= table-name (:table-name %)))
         first
         :table-id)))

(defn read-page-descriptor
  [db-title table-name page-id]
  (let [header (read-header db-title)
        page-descriptors (get-page-descriptors db-title table-name)
        page-descriptor (first (filter #(= page-id (:page-id %)) page-descriptors))]
    page-descriptor))

(declare calc-new-page-offset)

(defn get-page-descriptor
  [db-title table-name page-id]
  (let [loaded-page-descriptor (read-page-descriptor db-title table-name page-id)]
    (if (nil? loaded-page-descriptor)
        (let [offset (calc-new-page-offset db-title)]
          (generate-page-descriptor page-id offset))
        loaded-page-descriptor)))

(defn get-file-length
  [db-title]
  (with-open [raf (new java.io.RandomAccessFile (title->file db-title) "rwd")]
    (.length raf)))

(defn get-table-descriptors
  [db-title]
  (:table-descriptors (get-header db-title)))

(defn calc-new-page-offset
  [db-title]
  (let [table-descriptors (get-table-descriptors db-title)
        new-page-offset (+ PAGE-SIZE
                          (* PAGE-SIZE (if (= 0 (count table-descriptors))
                            0
                            (if (= 1 (count table-descriptors))
                              (count (:page-descriptors (nth table-descriptors 0)))
                              (reduce (fn [acc ts] (+ acc ts))
                                (map #(count (:page-descriptors %)) table-descriptors))))))]
    new-page-offset))

(defn add-page-descriptor
  [db-title table-name table-id page-id]
  (let [header (get-header db-title)
        table-descriptors (read-table-descriptors db-title)
        current-table-descriptor (read-table-descriptor db-title table-name)
        offset (calc-new-page-offset db-title)
        page-descriptor (get-page-descriptor db-title table-name page-id)
        filtered-table-descriptors (vec (filter #(not= table-name (:table-name %)) table-descriptors))
        new-page-descriptors (conj (get-page-descriptors db-title table-name) page-descriptor)
        new-table-descriptor (generate-table-descriptor
            (:table-id current-table-descriptor)
            (:table-name current-table-descriptor)
            (:attributes current-table-descriptor)
            (:index-descriptors current-table-descriptor)
            new-page-descriptors)
        new-table-descriptors (conj filtered-table-descriptors new-table-descriptor)
        new-header (generate-header new-table-descriptors)]
          (write-header db-title new-header)))

(defn add-page
  [db-title table-name table-id page-id]
  (let [new-page (generate-page table-id page-id)
        offset (calc-new-page-offset db-title)]
    (add-page-descriptor db-title table-name table-id page-id)
    (write-page db-title offset new-page)))

(defn get-page
  [db-title table-name page-id]
  (let [page-offset (read-page-offset db-title table-name page-id)
        table-id (get-table-id db-title table-name)
        real-page-offset (if (nil? page-offset)
            (do
              (add-page db-title table-name table-id page-id)
              (read-page-offset db-title table-name page-id))
            page-offset)
        loaded-page (read-page db-title real-page-offset)]
          loaded-page))

(defn insert
  [db-title table-name record]
    (let [page-id (calc-record-hash db-title table-name record)
          page (get-page db-title table-name page-id)
          page-offset (read-page-offset db-title table-name page-id)]
          (let [updated-records (conj (:records page) record)]
            (write-page db-title page-offset (generate-page (table-name->table-id db-title table-name) page-id updated-records)))))

;; Operators
(defn scan
  [db-title table-name]
  (let [page-descriptors (get-page-descriptors db-title table-name)]
  (mapcat vec
    (map #(:records (get-page db-title table-name (:page-id %))) page-descriptors))))

(defn projection
  ([db-title table-name]
  (map (fn [v]
    (filter (fn [c]
       (some #(= (:attribute-id c) %) (get-table-attributes db-title table-name))) v)) (scan db-title table-name)))
  ([db-title table-name attributes]
    (map (fn [v]
      (filter (fn [c]
         (some #(= (:attribute-id c) %) attributes)) v)) (scan db-title table-name))))

(defn attribute-id->keyword
  [db-title table-name a-id]
  (keyword
    (:name (first (filter #(= a-id (:id %)) (get-attributes db-title table-name))))))

(defn field->hash-map
 [db-title table-name field]
    (hash-map (attribute-id->keyword db-title table-name (:attribute-id field))
              (:value field)))

(defn project
  ([db-title table-name]
  (map #(apply merge %)
   (map #(map (fn [c] (field->hash-map db-title table-name c)) %) (projection db-title table-name))))
 ([db-title table-name attributes]
   (map #(apply merge %)
    (map #(map (fn [c] (field->hash-map db-title table-name c)) %) (projection db-title table-name attributes)))))


(defn select
  ([db-title table-name attr-preds]
  (map #(apply merge %)
  (->> attr-preds
  (map (fn [c]
    (filter (fn [v] (some
       #(and (= (first c) (:attribute-id %))
             ((second c) (:value %))) v)) (projection db-title table-name))))
  (map set)
  (reduce clojure.set/intersection)
  (vec)
  (map (fn [e] (map #(field->hash-map db-title table-name %) e))))))
([db-title table-name attr-preds set-pred]
  (map #(apply merge %)
  (->> attr-preds
  (map (fn [c]
    (filter (fn [v] (some
       #(and (= (first c) (:attribute-id %))
             ((second c) (:value %))) v)) (projection db-title table-name))))
  (map set)
  (reduce (if (= set-pred "or") clojure.set/union clojure.set/intersection))
  (vec)
  (map (fn [e] (map #(field->hash-map db-title table-name %) e)))))))


(defn remove-record
  [db-title table-name record]
  (let [page-id (calc-record-hash db-title table-name record)
        record-page (get-page db-title table-name page-id)
        table-id (get-table-id db-title table-name)
        new-page (generate-page table-id page-id (set (filter #(not= record %) (:records record-page))))]
    (write-page db-title (read-page-offset db-title table-name page-id) new-page)))

(defn nat-join
  [db-title table-name-1 table-name-2]
  (let [records-1 (project db-title table-name-1)
        records-2 (project db-title table-name-2)]
  (natural-join (set records-1) (set records-2))))

(defn cart-join
  [db-title table-name-1 table-name-2]
  (let [records-1 (project db-title table-name-1)
        records-2 (project db-title table-name-2)]
  (cross-join (set records-1) (set records-2))))
