# gooff

KISS scheduling library.  Inspired by [Snooze](https://github.com/leahneukirchen/snooze).

## Usage

Add to your project

    gooff "1.0.0"


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


### Cron

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

Call `(stop)` to kill the scheduling system, or pass in a name to kill an individual task.

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
