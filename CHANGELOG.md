# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
Nothing yet

## [0.0.6] - 2023-08-11
### Improved
- Change project coordinates inline with latest Clojars [recommendation](https://github.com/clojars/clojars-web/wiki/Verified-Group-Names)
- Bump up Clojure build version to 1.11.1
- Bump up Clojure test check version to 1.1.1

## [0.0.5] - 2023-08-11
### Improved
- Make library compatible with Clojure 1.11.0 and above

## [0.0.4] - 2021-02-23
### Improved
- `at` has potential to produce an uncaught exception.
   It now throws a custom `ex-info` which provides detailed information about the problem.

## [0.0.3] - 2020-12-16
### Fixed
- Tasks would reschedule if `stop` was called during execution.
  `stop` still worked if called while tasks were waiting to run.

### Added
- Users can now provide a callback to the function returned from `at`
  This will run your callback with the args you provided to `at`, instead of executing `f`.
- Users can now provide a callback function to `stop`.
  This is ran after the task is stopped and not rescheduled, and provides the callback
  with all the parameters which would have been supplied to the task on the next
  run.  This is helpful for doing 'safe' cleanup, because you won't be mucking
  about with a currently running task.

### Improved
- `restart` now calls `stop` with the new callback arity

### Note
- Any of the new callbacks provided should satisfy the predicate `fn?`.
- All of the old function arities still work as before.


## [0.0.2] - 2020-4-22
### Fixed
- Cron now accepts multi-digit exact matches

### Added
- `clear-schedule` function which does what you epect


## 0.0.1 - 2019-11-19
### Initial release

[Unreleased]: https://github.com/crinklywrappr/gooff/compare/v0.0.6...HEAD
[0.0.6]: https://github.com/crinklywrappr/gooff/compare/v0.0.5...v0.0.6
[0.0.5]: https://github.com/crinklywrappr/gooff/compare/v0.0.4...v0.0.5
[0.0.4]: https://github.com/crinklywrappr/gooff/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/crinklywrappr/gooff/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/crinklywrappr/gooff/compare/v0.0.1...v0.0.2
