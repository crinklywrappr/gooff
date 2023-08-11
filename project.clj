(defproject gooff "0.0.5"
  :description "KISS scheduling library. Inspired by Snooze"
  :url "https://github.com/doubleagent/gooff"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [clj-time "0.15.2"]]
  :repl-options {:init-ns gooff.core}
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.10.0"]]}}
  :repositories [["releases" {:url "https://repo.clojars.org"
                              :creds :gpg}]])
