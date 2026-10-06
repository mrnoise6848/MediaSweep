# ADR 001 — Lightweight Clean Architecture in a single module

**Status:** Accepted

## Context

MediaSweep must stay a small, reliable utility: a scanner, a review flow and a trash hand-off.
The specification requires a clear dependency direction (Compose → ViewModel → UseCase →
Repository → MediaStore/Room) but explicitly warns against turning the project into an
abstraction showcase or an unnecessary Gradle multi-module build.

## Decision

* Keep a single Gradle module (`app`) and enforce layering through packages:
  `domain` (pure Kotlin), `data` (implementations), `core` (database/DI/common),
  `feature` (UI), `scanner` (algorithms).
* `domain` defines repository interfaces and use cases; `data` implements them.
* ViewModels receive use cases through constructor injection from a manual `AppContainer`.

## Alternatives

* Multi-module (`:core:domain`, `:feature:home`, ...) — real compile-time isolation, but a
  much larger build surface for a project of this size; the specification forbids it for MVP.
* Hilt — automatic scoping and factory generation, at the cost of a compiler plugin and a
  second code-generation path next to Room's KSP. With one application-scoped container it
  buys little (see ADR 001b/decisions in code).
* No use cases, ViewModels talking to repositories directly — fewer types, but the scan and
  review rules would be duplicated across screens.

## Trade-offs

* Layering is convention + tests, not compiler-enforced.
* Some boilerplate interfaces exist even where only one implementation is planned.

## Consequences

* Domain logic is testable on the JVM with no Android dependencies.
* Adding a screen means: model/use case (if new rules) → repository method → ViewModel →
  Compose screen → route.
* The dependency graph stays visible in `AppContainer`.
