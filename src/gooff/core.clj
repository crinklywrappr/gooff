(ns gooff.core
  (:require [clj-time.core :as t]))

(defn parse-long [s]
  (Long/parseLong s))

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

(defprotocol IDateField
  (matches? [_ dt dp] "Returns true if the datepart (dp) of date matches the field"))

(defprotocol ITimeField
  (gen [_ dp] "returns a seq of all the exact matches for the given datepart (dp)"))

(defrecord FieldExact [n]
  IField
  (valid? [_ dp] (basic-valid? dp n))
  (fieldStr [_] (str n))
  IDateField
  (matches? [_ dt dp] (== (date-part dt dp) n))
  ITimeField
  (gen [_ dp] [n]))

(defrecord FieldAlternation [coll]
  IField
  (valid? [_ dp]
    (->> coll
         (map (partial basic-valid? dp))
         (every? true?)))
  (fieldStr [_] (str coll))
  IDateField
  (matches? [_ dt dp]
    (let [dp (date-part dt dp)]
      (some #(== dp %) coll)))
  ITimeField
  (gen [_ dp] coll))

(defrecord FieldRange [from to]
  IField
  (valid? [_ dp]
    (and (basic-valid? dp from)
         (basic-valid? dp to)))
  (fieldStr [_] (format "%s-%s" from to))
  IDateField
  (matches? [_ dt dp]
    (<= from (date-part dt dp) to))
  ITimeField
  (gen [_ dp] (range from to)))

(defrecord FieldStar []
  IField
  (valid? [_ dp] true)
  (fieldStr [_] "*")
  IDateField
  (matches? [_ dt dp] true)
  ITimeField
  (gen [_ dp]
    (case dp
      :hour (range 0 24)
      :minute (range 0 60)
      :second (range 0 60))))

(defrecord FieldRepetition [rep]
  IField
  (valid? [_ dp]
    (basic-valid? dp rep))
  (fieldStr [_] (format "/%s" rep))
  IDateField
  (matches? [_ dt dp]
    (zero? (mod (date-part dt dp) rep)))
  ITimeField
  (gen [_ dp]
    (range
     0
     (case dp
       :hour 24
       :minute 60
       :second 60)
     rep)))

(defrecord FieldShiftedRepetition [shift rep]
  IField
  (valid? [_ dp]
    (basic-valid? dp (+ rep shift)))
  (fieldStr [_] (format "%s/%s" shift rep))
  IDateField
  (matches? [_ dt dp]
    (zero? (mod (+ (date-part dt dp) (* shift -1)) rep)))
  ITimeField
  (gen [_ dp]
    (take-while
     (partial basic-valid? dp)
     (map
      #(+ % shift)
      (range
       0
       (case dp
         :hour 24
         :minute 60
         :second 60)
       rep)))))

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
    (clojure.string/includes? field "-") (field-range field)
    (= field "*") (field-star)
    (some? (re-matches repetition-pattern field)) (field-repetition field)
    (some? (re-matches shifted-pattern field)) (field-shifted-repetition field)
    :else (throw (IllegalArgumentException. (format "illegal field: %s" field)))))

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
        (update-in a [dp] conj field))
      {} rules)))
  ([] (rule {})))

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

(defn at [dt f & args]
  (.start
   (Thread.
    (fn []
      (Thread/sleep
       (t/in-millis
        (t/interval
         (tz (t/now)) dt)))
      (apply f args)))))
