# gooff

KISS scheduling library.  Inspired by [Snooze](https://github.com/leahneukirchen/snooze).

## Usage

Add to your project

    gooff "0.0.2"


## Quickstart

Pull it into your namespace

    (require '[gooff.core :refer :all])


### Rules

Scheduling rules are specified à la carte by calling `(rule ...)` eg this describes a schedule which executes every 5 seconds:

```clojure
;; the vectors allow you to specify additional rules with an 'OR' relationship.  More on this later.
(def myrule (rule {:hour ["*"] :minute ["*"] :second ["/5"]}))
```

Or you can call `(rule ...)` without any parameters to get the default rule, which expresses execution every day at midnight.


#### Cron

Additionally, rules can be created from basic cron syntax by calling `(cron ...)` eg this specifies execution which occurs every 5 minutes on weekdays in November & December:

```clojure
(cron "/5 * * 11,12 MON-FRI")
; =>
{:day-of-month ({}),
 :month ({:coll (11 12)}),
 :weekday ({:from 2, :to 6}),
 :day-of-year ({}),
 :week-of-year ({}),
 :hour ({}),
 :minute ({:rep 5}),
 :second ({:n 0})}
```

Both `(rule ...)` & `(cron ...)` validate their inputs, and throw an exception if input was invalid.


### Simulation

Now we want to check if our rule does what we want.  This is accomplished by calling `(simulate ...)`:

```clojure
(simulate myrule 10) ;; show the first 10 executions
; =>
(#object[org.joda.time.DateTime 0x6028a01c "2019-11-11T09:42:30.000-06:00"]
 #object[org.joda.time.DateTime 0x56941420 "2019-11-11T09:42:35.000-06:00"]
 #object[org.joda.time.DateTime 0x7e27329c "2019-11-11T09:42:40.000-06:00"]
 #object[org.joda.time.DateTime 0x8641c6c "2019-11-11T09:42:45.000-06:00"]
 #object[org.joda.time.DateTime 0x2f1b433d "2019-11-11T09:42:50.000-06:00"]
 #object[org.joda.time.DateTime 0x98e9691 "2019-11-11T09:42:55.000-06:00"]
 #object[org.joda.time.DateTime 0x4e7af6ee "2019-11-11T09:43:00.000-06:00"]
 #object[org.joda.time.DateTime 0x66d50431 "2019-11-11T09:43:05.000-06:00"]
 #object[org.joda.time.DateTime 0x13a2a17d "2019-11-11T09:43:10.000-06:00"]
 #object[org.joda.time.DateTime 0x6ee58786 "2019-11-11T09:43:15.000-06:00"])
```

Your dates will look different, but you should see the seconds counting up by 5 and landing on each 5th second.  Hooray!

#### Things to consider

`simulate` only searches for matching datetimes 2 years from when you run it, so if you aren't seeing the results you expect, that may be why eg

```clojure
;; asking for 10 results but i only get 2
(simulate (rule {:month [12] :day-of-month [1]}) 10)
;; =>
(#object[org.joda.time.DateTime 0x37cbc213 "2019-12-01T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x66600208 "2020-12-01T00:00:00.000-06:00"])
```


### Scheduling

Let's schedule something!

`(at ...)` is a low-level function which schedules a function to execute at a specific time.  Lets try it out!

```clojure
;; First we'll define a function to call...
(let [i (atom 0)]
  (defn i-presume [who]
    (println (format "%s: %s, I presume?" (swap! i inc) who))))

;; then we'll schedule it
(at (first (simulate myrule)) i-presume "Dr Livingstone")
;; prints "1: Dr Livingstone, I presume?" at the nearest 5th second
```

You can leverage this to do some makeshift scheduling eg:

```clojure
(map #(at % i-presume "Dr Livingstone") (simulate myrule 3))
;; prints 5 seconds apart:
;; 2: Dr Livingstone, I presume?
;; 3: Dr Livingstone, I presume?
;; 4: Dr Livingstone, I presume?
```

You may have noticed that `(at ...)` returned a function.  Call it to prevent execution eg

```clojure
;; won't print anything
((at (first (simulate myrule)) i-presume "Dr Livingstone"))
; => #<Promise@22bc5bc5: :stop>
```


### Scheduling Orchestration

`at` is a pretty good cornerstone for writing your own scheduling system.  However, `gooff` includes a simple system if you just want the basics.  Begin by calling `(add-schedule...)`.  We'll add two.

```clojure
;; Let's define another fn
(defn hello [who]
  (println (format "Hello, %s!" who)))

;; We'll schedule both of our tasks to execute at the same time
;; We give them a name as the first parameter so they can be referenced later
(add-schedule "i-presume" myrule i-presume)
(add-schedule "hello" myrule hello)
```


You'll notice that both of these functions return a map.  This is the schedule map and you can inspect it at any time using `(get-sched-map)`.

Let's kick them both off.

```clojure
(start "i-presume" "Dr Livingstone")
(start "hello" "World")
; => (some text is printed out
```

Feel free to call `(update-rules ...)`, `(update-fn ...)`, and `(restart ...)` in order to change the scheduling and execution characterisics.  This can be done live, *while the tasks are running*.

Call `(stop)` to kill the scheduling system, or pass in a name to kill an individual task.  Use `(clear-schedule)` & `(remove-schedule <nm>)` to clean up.

```clojure
(stop)
```

#### Things to consider

1. `(update-rules ...)` won't take effect until after the next scheduled execution.  To force the new rule, call `(restart ...)` afterward.


### Scheduling System Bonus

Included in the scheduling system is a functional way to provide new args to your tasks without calling `(restart ...)`.  It works similar to `(iterate ...)`: the output of each function call is used as the parameter input to the next.

```clojure
;; Lets use the famous collatz conjecture for testing!
(defn collatz [n]
  (println n)
  (if (even? n)
    (/ n 2)
    (inc (* 3 n))))
    
(add-schedule "collatz" myrule collatz)

(start-iterate "collatz" 12)
;; => (prints)
;; 12
;; 6
;; 10
;; 5
;; 16
;; 8
;; 4
;; 2
;; 1
;; 4
;; 2
;; 1
;; ...

;; after waiting a while ...
(stop)
```


## Rules (redux)

The `default-rule` does a good job explaining what dateparts are configurable.

```clojure
default-rule
{:day-of-month ["*"],
 :month ["*"],
 :weekday ["*"],
 :day-of-year ["*"],
 :week-of-year ["*"],
 :hour [0],
 :minute [0],
 :second [0]}
```

The following types of fields can be specified:

```clojure
;; exact match: run on the 3rd
(rule {:day-of-month [3]})

;; alternation: run on the 3rd, 10th, & 27th
(rule {:day-of-month [[3 10 27]]})

;; range: run on the 1st, 2nd, 3rd, 4th, & 5th
(rule {:day-of-month ["1-5"]})

;; star: run every day
(rule {:day-of-month ["*"]})

;; repetition: run on the 5th, 10th, 15th, 20th, 25th, 30th day
(rule {:day-of-month ["/5"]})

;; shifted repetition: run on the 7th, 12th, 17th, 22nd, 27th day
(rule {:day-of-month ["2/5"]})

;; negative shifted repetition: run on the 3rd, 8th, 13th, 18th, 23rd, 28th day
(rule {:day-of-month ["-2/5"]})
```

In order for a datetime to match a rule, it must match the field for *every* datepart.  However, you can specify that *any* match for a particular date-part constitutes a match for the entire datepart.  In other words, there is an *and* relationship between dateparts, and an *or* relationship _within_ dateparts.  For example:

```clojure
;; in this example january, february, june, november, and december are all valid 
;; between the 1st and the 10th, the 28th, and any day falling on a 5th offset by 15 days
(simulate (rule {:day-of-month ["1-10" "15/5" 28] :month ["1-2" 6 [11 12]]}) 10)
(#object[org.joda.time.DateTime 0x260eabab "2019-11-20T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x77918d75 "2019-11-25T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x33b20f3a "2019-11-28T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x1e26da59 "2019-11-30T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x659bb2a2 "2019-12-01T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x1e1a7141 "2019-12-02T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x3df27667 "2019-12-03T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x15ae8792 "2019-12-04T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x2d982927 "2019-12-05T00:00:00.000-06:00"]
 #object[org.joda.time.DateTime 0x6045c5ac "2019-12-06T00:00:00.000-06:00"])
```


### Caveat

`:day-of-week` is 1-7, SUN-SAT.  This differs from most cron/scheduling implementations.  I never liked that all of the other date components started counting at 1 while this one was different than the rest, so it made sense to normalize them.


## License

Copyright © 2019

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
