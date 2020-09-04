(ns guidescan-web.query.process
  "This namespace exposes an interface for processing gRNA queries that
  applies appropriate filters and sorts them correctly so that they
  are suitable for rendering."
  (:require [guidescan-web.bam.db :as db]
            [guidescan-web.genomics.grna :as grna]
            [guidescan-web.genomics.annotations :as annotations]
            [guidescan-web.query.parsing :refer [parse-query]]
            [failjure.core :as f]))
  
(defn- process-parsed-queries
  "Processes parsed queries, returning a failure object containing the
  first failed query on failure, and the unfiltered and unsorted,
  gRNAs for each [chrX, start, end] triple otherwise."
  [bam-db organism enzyme parsed-queries]
  (let [processed-queries
        (map #(apply db/query-bam-grna-db bam-db organism enzyme %) parsed-queries)]
     (if (some f/failed? processed-queries)
       (first (filter f/failed? processed-queries))
       processed-queries)))

(defn sort-results
  "Sorts the results of a gRNA query according to the ordering
  specified in the user request map."
  [ordering grnas [chromosone start-pos end-pos]]
  (case ordering
    "specificity" (sort-by :specificity grnas)
    "cutting-efficiency" (sort-by :cutting-efficiency grnas)
    (sort-by grna/num-off-targets grnas)))

(defn filter-results
  "Filters the results of a gRNA query according to the parameters
  specified in the user request map."
  [req grnas [chromosone start-pos end-pos]]
  (->> grnas
       (filter #(and (<= start-pos (:start %))
                     (>= end-pos (:end %))))))

(defn keep-only-top-n
  [top-nvalue grnas]
  (vec (take top-nvalue grnas)))

(defn annotate-grnas
  [gene-annotations organism grnas [chr _ _]]
  (let [annotate-grna
        (fn [{start :start end :end}]
          (annotations/get-annotations gene-annotations organism chr start end))]
    (map #(assoc % :annotations (annotate-grna %)) grnas)))

(defn process-query
  "Process the query, returning either a response vector containing the
  processed gRNAs for each [chrX, start, end] input or a failure
  object with an appropriate message."
  [bam-db gene-annotations req]
  (let [parsed-query (parse-query (:params req))
        enzyme (:enzyme (:params req))
        organism (:organism (:params req))
        topn-value (:topn-value (:params req))
        topn? (and (some? topn-value)
                   (boolean (re-find #"[0-9]+" topn-value))
                   (= "true" (:topn (:params req))))]
    (if-let [query (:success parsed-query)] ; else branch = parse error
      (f/attempt-all
       [vec-of-grnas (process-parsed-queries bam-db organism enzyme query)]
       (cond->> vec-of-grnas
            true (map #(filter-results req %2 %1) query)
            true (map #(sort-results (:ordering req) %2 %1) query)
            topn? (map #(keep-only-top-n (Integer/parseInt topn-value) %))
            true (map #(annotate-grnas gene-annotations organism %2 %1) query)
            true (map vector query)))
      (f/fail (:failure parsed-query)))))
