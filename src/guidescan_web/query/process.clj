(ns guidescan-web.query.process
  "This namespace exposes an interface for processing gRNA queries,
  applying appropriate filters and sorting them correctly so that they
  are suitable for rendering."
  (:require [guidescan-web.bam.db :as db]
            [guidescan-web.genomics.grna :as grna]
            [taoensso.timbre :as timbre]
            [guidescan-web.genomics.annotations :as annotations]
            [guidescan-web.query.parsing :refer [parse-request]]
            [guidescan-web.query.library-design :as library-design]
            [guidescan-web.utils :refer [revcom]]
            [failjure.core :as f]))

(defn- process-parsed-queries
  "Processes parsed queries, returning a failure object containing the
  first failed query on failure, and the unfiltered and unsorted,
  gRNAs for each [chrX, start, end] triple otherwise."
  [bam-db organism enzyme parsed-queries]
  (let [processed-queries
        (map #(apply db/query-bam-grna-db bam-db organism enzyme (:coords %)) parsed-queries)]
    (if (some f/failed? processed-queries)
      (first (filter f/failed? processed-queries))
      processed-queries)))

(defn sort-results
  "Sorts the results of a gRNA query according to the ordering
  specified in the user request map."
  [ordering grnas _]
  (case ordering
    "specificity" (sort-by :specificity grnas)
    "cutting-efficiency" (sort-by :cutting-efficiency grnas)
    (sort-by grna/num-off-targets grnas)))

(defn filter-results
  "Filters the results of a gRNA query according to the parameters
  specified in the user request map."
  [{:keys [cutting-efficiency-bounds specificity-bounds filter-annotated]}
   grnas
   genomic-region]
  (let [[chromosome start-pos end-pos] (:coords genomic-region)
        within (fn [k {:keys [lower upper]}]
                 (fn [grna]
                   (if-let [v (k grna)]
                     (and (<= lower v) (>= upper v))
                     true)))]
    (cond->> grnas
      true (filter #(and (<= start-pos (:start %))
                         (>= end-pos (:end %))))
      cutting-efficiency-bounds (filter (within :cutting-efficiency cutting-efficiency-bounds))
      specificity-bounds (filter (within :specificity specificity-bounds))
      filter-annotated (filter #(not-empty (:annotations %))))))

(defn keep-only-top-n
  "Returns only the top-n grnas."
  [top-nvalue grnas]
  (vec (take top-nvalue grnas)))

(defn annotate-grnas
  "Annotates the grnas."
  [gene-annotations organism grnas genomic-region]
  (timbre/debug genomic-region)
  (let [cut-offset 6
        accession (first (:coords genomic-region))
        annotate-grna
        (fn [{start :start end :end rna :sequence dir :direction}]
          (let [check-pos (if (= dir :positive) (- end cut-offset) (+ start cut-offset))]
            (annotations/get-annotations gene-annotations accession check-pos check-pos)))]
    (map #(assoc % :annotations (annotate-grna %)) grnas)))

(defn split-region-flanking
  [{[chr start end] :coords r :region-name c :chromosome-name} flanking-value]
  [{:region-name (str r ":left-flank")
    :chromosome-name c
    :coords [chr (- start (- flanking-value 1)) start]}
   {:region-name (str r ":right-flank")
    :chromosome-name c
    :coords [chr end (+ end (- flanking-value 1))]}])

(defn convert-regions
  "Converts genomic regions of the form,
      {:region-name name, :coords [chr, start, end]}
  to ones of the form,
      {:region-name name
       :organism organism
       :coords [chr, start, end]}
  converting each region into two when
  we are in flanking mode."
  [genomic-regions organism flanking]
  (let [genomic-regions
        (cond->> genomic-regions
                 flanking (map #(split-region-flanking % flanking))
                 flanking (apply concat)
                 true (map #(assoc % :organism organism)))
        genomic-regions-size
        (reduce + (map #(- (last (:coords %)) (second (:coords %))) genomic-regions))]
    (if (> genomic-regions-size 1e7)
      (f/fail "Parsed genomic regions length exceeds 10M, the maximum allowed.")
      genomic-regions)
    ))

(defn- wrap-result
  [query-type result]
  {:query-type query-type
   :result result})

(defmulti process-query
  "Process the query, returning a map of the form:

     {:query-type t :result r}

  On success or a failure object with an appropriate
  message."
  (fn [_ req] (keyword (get-in req [:params :query-type] :standard))))

(defmethod process-query :standard
  [{:keys [bam-db gene-annotations gene-resolver sequence-resolver]}
   req]
  (f/attempt-all
   [{:keys [genomic-regions
            enzyme
            organism
            filter-annotated
            topn
            cutting-efficiency-bounds
            specificity-bounds
            flanking]}
    (parse-request :standard
                   {:gene-resolver gene-resolver :sequence-resolver sequence-resolver}
                   req)
    converted-regions (convert-regions genomic-regions organism flanking)
    vec-of-grnas (process-parsed-queries bam-db organism enzyme converted-regions)
    filter-opts {:filter-annotated filter-annotated
                 :cutting-efficiency-bounds cutting-efficiency-bounds
                 :specificity-bounds specificity-bounds}]
   (do
     (timbre/info :statistics
                  {:num-genomic-regions (count converted-regions)
                   :query-type :standard
                   :organism organism
                   :enzyme enzyme})
     (cond->> vec-of-grnas
       true (map #(annotate-grnas gene-annotations organism %2 %1) converted-regions)
       true (map #(filter-results filter-opts %2 %1) converted-regions)
       true (map #(sort-results "num-off-targets" %2 %1) converted-regions)
       (some? topn) (map #(keep-only-top-n topn %))
       true (map vector converted-regions)
       true (wrap-result :standard)))))

(defn- find-grna
  [grna intersecting-grnas]
  (let [get-seq #(if (= (:direction %) :positive) (subs (:sequence %) 0 20)
                                                  (subs (revcom (:sequence %)) 0 20))
        hamming-distance #(count (filter identity (map = %1 %2)))
        close-grnas (filter 
          #(or (<= 20 (hamming-distance (:region-name grna) (get-seq %)))
               (<= 20 (hamming-distance (revcom (:region-name grna)) (get-seq %))))
          intersecting-grnas)]
    (first close-grnas)))

(defmethod process-query :grna
  [{:keys [bam-db gene-annotations sequence-resolver]}
   req]
  
  (f/attempt-all
   [{:keys [enzyme
            organism
            genomic-regions]}
    (parse-request :grna {:sequence-resolver sequence-resolver} req)
    good-genomic-regions (filter #(not (:error %)) genomic-regions)
    bad-genomic-regions (filter :error genomic-regions)
    converted-regions (convert-regions good-genomic-regions organism false)
    vec-of-grnas (process-parsed-queries bam-db organism enzyme converted-regions)]
   (do
     (timbre/info :statistics
                  {:num-successful-sequences (count good-genomic-regions)
                   :num-unsuccessful-sequences (count bad-genomic-regions)
                   :query-type :sequence-search
                   :organism organism
                   :enzyme enzyme})
     (->>
       (map find-grna good-genomic-regions vec-of-grnas)
       (map #(if (nil? %2)
               {:error {:message (str "Guide not found in Guidescan2 database. This is because it has "
                                      "multiple off-targets at distance 1.")}
                :grna (:region-name %1)
                :genomic-region %1}
               (assoc %2 :genomic-region %1)) good-genomic-regions)
       (concat bad-genomic-regions)
       (wrap-result :grna)))))

(defmethod process-query :library
  [{:keys [bam-db gene-annotations resolver db-pool]}
   req]
  (f/attempt-all
   [{:keys [query-text organism] :as options} (parse-request :library {} req)
    result (library-design/design-library db-pool query-text organism options)]
   (wrap-result :library result)))
