(ns gooff.core
  (:refer-clojure :exclude [parse-long])
  (:require [clj-time.core :as t]
            [clojure.string :as s]))

;; --- UTIL ---

(defn ^:private parse-long [s]
  (Long/parseLong s))

;; --- DATE-TIME ---

;; convenience
(defn- tz
  "Returns the datetime translated to the local timezone"
  [dt]
  (t/to-time-zone dt (t/default-time-zone)))

(defn now
  "Returns the current date-time in the local timezone"
  []
  (tz (t/now)))

;; needed for scheduling fields
(defn weekday
  "Returns the weekday 1-7, with Sunday=1 & Saturday=7
  This differs from most cron implementations on purpose.
  I never liked that years and months began counting at 1,
  but weekdays did not."
  [dt]
  (if (== (t/day-of-week dt) 7)
    1 (inc (t/day-of-week dt))))

(defn day-of-year
  "Returns the day of the year, eg 1-365"
  [dt]
  (inc
   (t/in-days
    (t/interval
     (t/date-time
      (t/year dt))
     (t/date-time
      (t/year dt)
      (t/month dt)
      (t/day dt))))))

(defn week-number-of-year
  "Returns the week number of the year eg 1-52"
  [dt]
  (t/week-number-of-year dt))

(defn date-part
  "Returns the datepart (dp) of dt.  dp values are:
  :year :month :day :hour :minute :second
  :week-of-year :day-of-year :day-of-month :weekday"
  [dt dp]
  (case dp
    ;; standard
    :year (t/year dt)
    :month (t/month dt)
    :day (t/day dt)

    :hour (t/hour dt)
    :minute (t/minute dt)
    :second (t/second dt)

    ;; non-standard
    :week-of-year (week-number-of-year dt)

    :day-of-year (day-of-year dt)
    :day-of-month (t/day dt)
    :weekday (weekday dt)

    nil))

;; needed for simulation

(defn today
  "returns start-of-day according to the local time-zone"
  []
  (let [dt (now)]
    (t/minus
     dt (t/hours (t/hour dt))
     (t/minutes (t/minute dt))
     (t/seconds (t/second dt))
     (t/millis (t/milli dt)))))

(defn next-year
  "Adds a year to the given datetime"
  [dt]
  (t/plus dt (t/years 1)))

(defn tomorrow
  "Adds a day to the given datetime"
  [dt]
  (t/plus dt (t/days 1)))

(defn before?
  "Returns true if a < b,
  a & b are both date-times"
  [a b]
  (t/before? a b))

(defn add-time
  "Add the time parameters the the given date"
  [dt hours minutes seconds]
  (t/plus
   dt (t/hours hours)
   (t/minutes minutes)
   (t/seconds seconds)))

;; needed for scheduling
(defn millis-from-now
  "Given a datetime ahead of the current
  local datetime, returns the difference
  in milliseconds"
  [dt]
  (t/in-millis
   (t/interval
    (now) dt)))

;; --- SCHEDULING FIELDS ---

(defn basic-valid?
  "returns true if the numer n is
  'in range' for the given datepart (dp)"
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
    (and (pos? rep) (basic-valid? dp rep)))
  (fieldStr [_] (format "/%s" rep)))

(defrecord FieldShiftedRepetition [shift rep]
  IField
  (valid? [_ dp]
    (and (pos? rep)
         (or (neg? shift) (pos? shift))
         (basic-valid? dp (+ rep shift))))
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
    (if (and (some? shift) (some? rep)
             (or (neg? shift) (pos? shift))
             (pos? rep))
      (->FieldShiftedRepetition shift rep)
      (throw
       (IllegalArgumentException.
        "field-shifted-repetition expects a string in the form of n/n")))))

(defn parse-field
  "Given a scheduling field eg
  '*', 1 [1 2 3] '/5' '-2/5'
  returns the appropriate type"
  [field]
  (cond
    (int? field) (field-exact field)
    (coll? field) (field-alternation field)
    (some? (re-matches repetition-pattern field)) (field-repetition field)
    (some? (re-matches shifted-pattern field)) (field-shifted-repetition field)
    (s/includes? field "-") (field-range field)
    (= field "*") (field-star)
    :else (throw (IllegalArgumentException. (format "illegal field: %s" field)))))

;; --- RULES ---

;; default rule is every day at midnight
(def default-rule
  ^{:doc "The default rule expresses execution every day at midnight"}
  {:day-of-month ["*"]
   :month        ["*"]
   :weekday      ["*"]
   :day-of-year  ["*"]
   :week-of-year ["*"]
   :hour         [ 0 ]
   :minute       [ 0 ]
   :second       [ 0 ]})

(defn- parse-rules [m]
  (->> m
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

(defn- validate-rules [rules]
  (doseq [[dp f] rules]
    (when (not (valid? f dp))
      (throw
       (IllegalArgumentException.
        (format "%s is an invalid rule for %s" (fieldStr f) dp))))))

(defn rule
  "Allows you to specify rules a-la-cart, and anything missing
  will use the default rules eg `{:day-of-year [\"/5\"]}` will
  return a rule which describes execution every 5 days at midnight.

  With no args it uses the default rule.

  Also performs validation.  Throws an exception if invalid."
  ([m]
   (let [rules (parse-rules m)
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

(defn translate-cron
  "Given a cron field (string), translates it to input
  which the rule function will understand."
  [field]
  (cond
    (re-find #"^\d+$" field) (parse-long field)
    (s/includes? field ",") (map parse-long (s/split field #","))
    :else field))

(defn substitute-days
  "substitutes day names eg SUN-SAT in the cron
  string for their numeric representations eg 1-7"
  [s]
  (reduce
   (fn [a [abbr n]]
     (s/replace a abbr n))
   s day-abbrs))

(defn- parse-cron [s]
  (map translate-cron
       (-> s s/lower-case
           substitute-days
           (s/split #" "))))

(defn- validate-cron [coll]
  (if (== (count coll) 5)
    coll
    (throw
     (IllegalArgumentException.
      "Cron needs exactly 5 fields eg * * * * *"))))

(defn cron
  "Takes a cron string eg \"* * * * *\" and returns
  the corresponding rule.  Uses standard order of:
    - minute
    - hour
    - day of the month
    - month
    - day of the week

  Performs validation.  Throws an exception if invalid.

  An example:
  `\"/5 * * 11,12 MON-FRI\"` Every 5 minutes on weekdays in Nov & Dec"
  [s]
  (let [[minute hour day-of-month month weekday] (validate-cron (parse-cron s))]
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
        (mod (:rep this))
        zero?))

  FieldShiftedRepetition
  (matches? [this dt dp]
    (let [dp (date-part dt dp)]
      (when (not= dp (:shift this))
        (-> (+ dp (* (:shift this) -1))
            (mod (:rep this))
            zero?)))))

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

(defn days-seq
  "Returns a seq of days between from & to"
  [from to]
  (take-while
   #(before? % to)
   (iterate tomorrow from)))

(defn before-now?
  "Returns true if dt is before
  the current local datetime"
  [dt]
  (before? dt (now)))

(defn date-matches?
  "Returns true if the rule describe that
  execution will occur on the given date
  (time component is not under consideration
  in this function)"
  [rules dt]
  (every?
   (fn [[dp fs]]
     (some #(matches? % dt dp) fs))
   (select-keys
    rules [:day-of-month :month :weekday
           :day-of-year :week-of-year])))

(defn time-gen
  "Returns a sequence of numbers which are
  exact matches for the given datepart."
  [rules dp]
  (->> rules dp
       (map #(gen % dp))
       (apply concat)
       distinct
       (sort <)))

(defn simulate
  "simulates executions within a year by returning
  a seq of the datetimes which meet the rules"
  ([rules]
   (->>
    (for [d (filter
             (partial date-matches? rules)
             (days-seq
              (today)
              (next-year
               (next-year
                (today)))))
          h (time-gen rules :hour)
          m (time-gen rules :minute)
          s (time-gen rules :second)]
      (add-time d h m s))
    (drop-while before-now?)))
  ([rules n]
   (take n (simulate rules))))

;; --- SCHEDULING ---

(defn at
  "Specify the function f to run at a particular datetime.
  Returns a function which will prevent execution of f when called.
  Calling that function by passing a fn, results in the provided new function being called with args"
  [dt f & args]
  (let [trigger (promise)]
    (.start
     (Thread.
      (fn []
        (cond
          (= @trigger :go) (apply f args)
          (fn? @trigger) (apply @trigger args)))))
    (.start
     (Thread.
      (fn []
        (try
          (Thread/sleep
           (millis-from-now dt))
          (deliver trigger :go)
          (catch Exception e
            (throw
             (ex-info
              "gooff: Unable to schedule function or next task execution"
              {:dt dt :f f :args args} e)))))))
    (fn
      ([] (deliver trigger :stop))
      ([f] (deliver trigger f)))))

(def
  ^{:private true
    :doc "private in order to protect some delicate state management"}
  sched-map
  (atom {}))

(defn get-sched-map
  "Returns the schedule map (dereffed)"
  []
  @sched-map)

(defn status
  "Returns the status of a given process"
  [nm]
  (get-in @sched-map [nm :status]))

(defn add-schedule
  "Adds a task to the sched-map.  Expects a name (nm), rules for
  describing execution eg `(rule ...)` or `(cron ...)`, a function f
  and args which will be passed to f.
  eg `(add-schedule \"test\" (rule) (fn [] ...))`"
  [nm rules f]
  (when (not (contains? @sched-map nm))
    (swap! sched-map assoc nm {:rules rules :fn f})))

(defn remove-schedule
  "Removes a task from sched-map if it is not running"
  [nm]
  (when (nil? (status nm))
    (swap! sched-map dissoc nm)))

(defn clear-schedule
  "Removes all non-running tasks from sched-map"
  []
  (doseq [k (keys @sched-map)]
    (remove-schedule k)))

(defn update-rules
  "Alter the execution schedule of a
  process.  Can be done while running.
  If you need this to take effect
  immediately, call `(restart ...)`"
  [nm rules]
  (when (contains? @sched-map nm)
    (swap! sched-map assoc-in [nm :rules] rules)))

(defn update-fn
  "Updates the function associated with the task nm.
  Can be done while the task is running."
  [nm f]
  (when (contains? @sched-map nm)
    (swap! sched-map assoc-in [nm :fn] f)))

(declare start-aux)

(defn- sched-fn [nm & args]
  (apply (get-in @sched-map [nm :fn]) args)
  (apply trampoline (concat [start-aux nm sched-fn] args)))

(defn- iterate-fn [nm & args]
  (let [args (apply (get-in @sched-map [nm :fn]) args)]
    (apply trampoline (concat [start-aux nm iterate-fn] [args]))))

(defn- start-aux [nm f & args]
  (if (= (status nm) :running)
    (let [next-run (-> @sched-map
                       (get-in [nm :rules])
                       simulate first)]
      (->>
       (->
        [next-run f nm]
        (concat args))
       (apply at)
       (swap!
        sched-map assoc-in
        [nm :stop]))
      (swap!
       sched-map assoc-in
       [nm :next-run] next-run))
    (when-let [cb (get-in @sched-map [nm :cb])]
      (swap! sched-map
             assoc nm (dissoc (get @sched-map nm) :cb))
      (apply cb args))))

(defn start
  "kick off task execution."
  [nm & args]
  (when (and (contains? @sched-map nm) (not= (status nm) :running))
    (swap! sched-map assoc-in [nm :status] :running)
    (apply start-aux (concat [nm sched-fn] args))))

(defn start-iterate
  "kicks off iterative task execution.  This uses the
  output of each function call as the input of the next"
  [nm & args]
  (when (and (contains? @sched-map nm) (not= (status nm) :running))
    (swap! sched-map assoc-in [nm :status] :running)
    (swap! sched-map assoc-in [nm :type] :iterative)
    (apply start-aux (concat [nm iterate-fn] args))))

(defn stop
  "Stop task execution.
  With no args, stops all task execution.
  Optional callback function can be provided for when execution is ended.
  Callback will recieve args which would have went to next function call.

  This is slightly complicated, because under the covers there are two
  execution stacks which can potentially run your `cb`.  One is from
  `start-aux`, which gets called if the task was executing during `stop`.
  The other is from `at`, which runs your callback if it was waiting to
  execute."
  ([nm cb]
   (when-let [f (get-in @sched-map [nm :stop])]
     (swap!
      sched-map assoc nm
      (-> @sched-map
          (get nm)
          (select-keys [:rules :fn])
          (assoc :cb cb)))
     (f cb)))
  ([nm]
   (stop nm (constantly nil)))
  ([]
   (doseq [nm (keys @sched-map)]
     (stop nm (constantly nil)))))

(defn restart
  "Restarts task execution."
  [nm & args]
  (let [typ (get-in @sched-map [nm :type])]
    (stop nm
          (fn [& _]
            (case typ
              nil (apply start (concat [nm] args))
              :iterative (apply start-iterate (concat [nm] args)))))))
