(ns gooff.core
  (:require [clj-time.core :as t]
            [clojure.string :as s]))

;; --- UTIL ---

(defn parse-long [s]
  (Long/parseLong s))

;; --- DATE-TIME ---

(defn weekday [dt]
  (if (== (t/day-of-week dt) 7)
    1 (inc (t/day-of-week dt))))

(defn day-of-year [dt]
  (t/in-days
   (t/interval
    (t/minus
     dt (t/days (t/day dt))
     (t/months (t/month dt))
     (t/hours (t/hour dt))
     (t/minutes (t/minute dt))
     (t/seconds (t/second dt))
     (t/millis (t/milli dt)))
    dt)))

(defn tz [dt]
  (t/to-time-zone dt (t/default-time-zone)))

(defn today
  "returns start-of-day according to the local time-zone"
  []
  (let [dt (tz (t/now))]
    (t/minus
     dt (t/hours (t/hour dt))
     (t/minutes (t/minute dt))
     (t/seconds (t/second dt))
     (t/millis (t/milli dt)))))

(defn year-from-today []
  (t/interval (today) (t/plus (today) (t/years 1))))

(defn tomorrow [dt]
  (t/plus dt (t/days 1)))

(defn days-seq [interval]
  (->> (t/start interval)
       (iterate tomorrow)
       (take-while
        #(t/before?
          % (t/end interval)))))

(defn millis-from-now [dt]
  (t/in-millis
   (t/interval
    (tz (t/now)) dt)))

;; --- SCHEDULING FIELDS ---

(defn basic-valid?
  [dp n]
  (or
   (and (= (dp :day-of-month)) (> n 0))
   (and (= dp :month) (< 0 n 13))
   (and (= dp :weekday) (< 0 n 8))
   (and (= (dp :day-of-year)) (> n 0))
   (and (= (dp :week-of-year)) (> n 0))
   (and (= dp :hour) (<= 0 n 23))
   (and (= dp :minute) (<= 0 n 59))
   (and (= dp :second) (<= 0 n 59))))

(defn date-part [dt dp]
  (case dp
    :day-of-month (t/day dt)
    :month (t/month dt)
    :weekday (weekday dt)
    :day-of-year (day-of-year dt)
    :week-of-year (t/week-number-of-year dt)
    nil))

(defprotocol IField
  (valid? [_ dp] "Returns true if the field is valid for the given datepart")
  (fieldStr [_] "Returns a string representing the field"))

(defrecord FieldExact [n]
  IField
  (valid? [_ dp] (basic-valid? dp n))
  (fieldStr [_] (str n)))

(defrecord FieldAlternation [coll]
  IField
  (valid? [_ dp]
    (->> coll
         (map (partial basic-valid? dp))
         (every? true?)))
  (fieldStr [_] (str coll)))

(defrecord FieldRange [from to]
  IField
  (valid? [_ dp]
    (and (basic-valid? dp from)
         (basic-valid? dp to)))
  (fieldStr [_] (format "%s-%s" from to)))

(defrecord FieldStar []
  IField
  (valid? [_ dp] true)
  (fieldStr [_] "*"))

(defrecord FieldRepetition [rep]
  IField
  (valid? [_ dp]
    (basic-valid? dp rep))
  (fieldStr [_] (format "/%s" rep)))

(defrecord FieldShiftedRepetition [shift rep]
  IField
  (valid? [_ dp]
    (basic-valid? dp (+ rep shift)))
  (fieldStr [_] (format "%s/%s" shift rep)))

(def range-pattern #"^(\d+)-(\d+)$")
(def repetition-pattern #"^/(\d+)$")
(def shifted-pattern #"^(-?\d+)/(\d+)$")

(defn field-exact [n]
  (if (int? n)
    (->FieldExact n)
    (throw
     (IllegalArgumentException.
      "field-exact expects an integer"))))

(defn field-alternation [coll]
  (if (and (coll? coll) (every? int? coll))
    (->FieldAlternation (distinct coll))
    (throw
     (IllegalArgumentException.
      "field-alternation expects an integer collection"))))

(defn field-range [s]
  (let [[from to] (->> s
                       (re-find range-pattern)
                       rest (map parse-long))]
    (when (or (nil? from) (nil? to))
      (throw
       (IllegalArgumentException.
        "field-range expects a string of the form n-n")))
    (if (< from to)
      (->FieldRange from to)
      (throw
       (IllegalArgumentException.
        "ensure from < to in your field-range")))))

(defn field-star [] (->FieldStar))

(defn field-repetition [s]
  (let [rep (try
            (-> repetition-pattern
                (re-find s)
                last parse-long)
            (catch Exception e
              (throw
               (IllegalArgumentException.
                "field-repetition expects a string in the form of /n"))))]
    (->FieldRepetition rep)))

(defn field-shifted-repetition [s]
  (let [[shift rep] (->> s
                         (re-find shifted-pattern)
                         rest (map parse-long))]
    (if (and (some? shift) (some? rep) (pos? (+ rep shift)))
      (->FieldShiftedRepetition shift rep)
      (throw
       (IllegalArgumentException.
        "field-shifted-repetition expects a string in the form of n/n")))))

(defn parse-field [field]
  (cond
    (int? field) (field-exact field)
    (coll? field) (field-alternation field)
    (s/includes? field "-") (field-range field)
    (= field "*") (field-star)
    (some? (re-matches repetition-pattern field)) (field-repetition field)
    (some? (re-matches shifted-pattern field)) (field-shifted-repetition field)
    :else (throw (IllegalArgumentException. (format "illegal field: %s" field)))))

;; --- RULES ---

;; default rule is every day at midnight
(def default-rule
  {:day-of-month ["*"]
   :month        ["*"]
   :weekday      ["*"]
   :day-of-year  ["*"]
   :week-of-year ["*"]
   :hour         [ 0 ]
   :minute       [ 0 ]
   :second       [ 0 ]})

(defn parse-rules [rules]
  (->> rules
       (merge default-rule)
       (map
        (fn [[k v]]
          (->>
           v
           (map parse-field)
           (remove nil?)
           (map (partial vector k)))))
       (remove empty?)
       (mapcat identity)))

(defn validate-rules
  [rules]
  (doseq [[dp f] rules]
    (when (not (valid? f dp))
      (throw
       (IllegalArgumentException.
        (format "%s is an invalid rule for %s" (fieldStr f) dp))))))

(defn rule
  ([rules]
   (let [rules (parse-rules rules)
         _ (validate-rules rules)]
     (reduce
      (fn [a [dp field]]
        (update a dp conj field))
      {} rules)))
  ([] (rule {})))

;; --- CRON ---

(def day-abbrs
  {"sun" "1"
   "mon" "2"
   "tue" "3"
   "wed" "4"
   "thu" "5"
   "fri" "6"
   "sat" "7"})

(defn sub-days [field]
  (reduce
   (fn [a [abbr n]]
     (s/replace a abbr n))
   field day-abbrs))

(defn translate-cron [field]
  (cond
    (re-find #"^\d$" field) (parse-long field)
    (s/includes? field ",") (map parse-long (s/split field #","))
    :else field))

(defn parse-cron [s]
  (map translate-cron
       (-> s s/lower-case
           sub-days (s/split #" "))))

(defn cron [s]
  (let [[minute hour day-of-month month weekday] (parse-cron s)]
    (rule {:minute [minute] :hour [hour]
           :day-of-month [day-of-month]
           :month [month] :weekday [weekday]})))

;; --- SIMULATION ---

(defprotocol IDateField
  (matches? [_ dt dp] "Returns true if the datepart (dp) of date matches the field"))

(defprotocol ITimeField
  (gen [_ dp] "returns a seq of all the exact matches for the given datepart (dp)"))

(extend-protocol IDateField

  FieldExact
  (matches? [this dt dp]
    (== (date-part dt dp) (:n this)))

  FieldAlternation
  (matches? [this dt dp]
    (let [dp (date-part dt dp)]
      (some #(== dp %) (:coll this))))

  FieldRange
  (matches? [this dt dp]
    (<= (:from this)
        (date-part dt dp)
        (:to this)))

  FieldStar
  (matches? [_ dt dp] true)

  FieldRepetition
  (matches? [this dt dp]
    (-> (date-part dt dp)
        (mod  (:rep this))
        zero?))

  FieldShiftedRepetition
  (matches? [this dt dp]
    (-> (date-part dt dp)
        (+  (* (:shift this) -1))
        (mod (:rep this))
        zero?)))

(extend-protocol ITimeField

  FieldExact
  (gen [this dp] [(:n this)])

  FieldAlternation
  (gen [this dp] (:coll this))

  FieldRange
  (gen [this dp]
    (range (:from this) (:to this)))

  FieldStar
  (gen [_ dp]
    (case dp
      :hour (range 0 24)
      :minute (range 0 60)
      :second (range 0 60)))

  FieldRepetition
  (gen [this dp]
    (range
     0
     (case dp
       :hour 24
       :minute 60
       :second 60)
     (:rep this)))

  FieldShiftedRepetition
  (gen [this dp]
    (take-while
     (partial basic-valid? dp)
     (map
      #(+ % (:shift this))
      (range
       0
       (case dp
         :hour 24
         :minute 60
         :second 60)
       (:rep this))))))

(defn date-matches? [rules dt]
  (every?
   (fn [[dp fs]]
     (some #(matches? % dt dp) fs))
   (select-keys
    rules [:day-of-month :month :weekday
           :day-of-year :week-of-year])))

(defn time-gen [rules dp]
  (->> rules dp
       (map #(gen % dp))
       (apply concat)
       distinct
       (sort <)))

(defn simulate
  "simulates the first n executions within a year by
  returning a seq of the datetimes which meet the rules"
  [rules n]
  (->>
   (for [d (filter
            (partial date-matches? rules)
            (days-seq (year-from-today)))
         h (time-gen rules :hour)
         m (time-gen rules :minute)
         s (time-gen rules :second)]
     (t/plus
      d (t/hours h)
      (t/minutes m)
      (t/seconds s)))
   (drop-while
    #(t/before? % (tz (t/now))))
   (take n)))

;;  --- SCHEDULING ---

(defn at [dt f & args]
  (let [trigger (promise)]
    (.start
     (Thread.
      (fn []
        (when (not= @trigger :stop)
          (apply f args)))))
    (.start
     (Thread.
      (fn []
        (Thread/sleep
         (millis-from-now dt))
        (deliver trigger :go))))
    (fn [] (deliver trigger :stop))))

(def ^{:private true}
  sched-map
  (atom {}))


(defn status [nm]
  (get-in @sched-map [nm :status]))

(defn add-schedule [nm rules f]
  (when (nil? (status nm))
    (swap! sched-map assoc nm {:rules rules :fn f})))

(defn remove-schedule [nm]
  (when (nil? (status nm))
    (swap! sched-map dissoc nm)))

(defn update-rules [nm rules]
  (swap! sched-map assoc-in [nm :rules] rules))

(defn update-fn [nm f]
  (swap! sched-map assoc-in [nm :fn] f))

(declare start-aux)

(defn- sched-fn [nm & args]
  (apply (get-in @sched-map [nm :fn]) args)
  (apply trampoline (concat [start-aux nm] args)))

(defn- start-aux [nm & args]
  (->>
   (->
    [(-> @sched-map (get-in [nm :rules])
         (simulate 1) first)
     sched-fn nm]
    (concat args))
   (apply at)
   (swap!
    sched-map assoc-in
    [nm :stop])))

(defn start [nm & args]
  (when (not= (status nm) :running)
    (apply start-aux (concat [nm] args))
    (swap! sched-map assoc-in [nm :status] :running)))

(defn stop
  ([nm]
   (when-let [f (get-in @sched-map [nm :stop])]
     (f)
     (swap!
      sched-map assoc nm
      (select-keys
       (get @sched-map nm)
       [:rules :fn]))))
  ([]
   (doseq [nm (keys @sched-map)]
     (stop nm))))
