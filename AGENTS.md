# Working on Butler

Instructions for any coding agent or engineer picking up Butler work: Claude Code, Codex, Cursor, Gemini CLI or a person. Read this file, then [docs/plan/STATUS.md](docs/plan/STATUS.md), then the doc for your lane.

## What Butler is

Butler rewrites Clawcode (`ultraworkers/claw-code`, a Rust terminal coding agent) in Kotlin and Spring Boot. It is a single local process: the CLI, the agent loop and the tools run in one JVM, acting on the folder they are started from.

| Module | Holds | May depend on |
| --- | --- | --- |
| `core` | Domain types, use cases (`usecase/**`, names end in `UseCase`), ports (`gateway/**`, interfaces only) | Nothing but the Kotlin standard library and the JDK |
| `secondary-adaptor` | Provider clients, persistence (files and PostgreSQL), tool implementations, Spring config for them | `core` |
| `api-primary-adaptor` | The CLI (picocli), Spring Boot main, wiring of use cases (`config/UseCaseConfig.kt`) | `core` at compile time, `secondary-adaptor` at runtime only |

## Rules the build enforces

- `./gradlew build` must pass: tests, ArchUnit (`api-primary-adaptor/src/test/.../ArchitectureTest.kt`) and `verifyCoreIsFrameworkFree`. Keep it free of compiler warnings too.
- `core` never imports Spring, kotlinx-serialization or any library, and never parses JSON: JSON stays `JsonText`, adaptors parse it.
- Naming: classes in `secondary.gateway.impl..` end in `GatewayImpl`; `secondary.client..mapper..` in `ClientMapper`; `secondary.persistence..mapper..` in `PersistenceMapper`; `api.cli.mapper..` in `CliMapper`. A top-level function in one of these packages compiles to a `...Kt` class and breaks the rule, so keep helpers as private members.
- Three-layer mapping: persistence entities and client DTOs never use core types; mappers translate.

## Conventions

- Kotlin 2.3, JDK 21 toolchain, Gradle Kotlin DSL, JUnit 6. Immutable data classes, constructor injection, use cases as plain classes wired in `UseCaseConfig`.
- Comments explain why, not what. KDoc on public types.
- Configuration through `butler.*` properties with environment overrides. No secrets in source: keys come from environment variables such as `ANTHROPIC_API_KEY`.
- Tests at the layer you change: core with the in-memory fakes in `core/src/testFixtures` (`ToolDoubles.kt`, `TurnDoubles.kt`, `ModelDoubles.kt`, ...); adaptors against temp folders and `FakeProviderServer`; PostgreSQL through Testcontainers (skipped without Docker); the CLI end to end.
- Behaviour, names and wording follow Clawcode where it has them. Read its source (commit `22fe360`, v0.1.7) in a separate checkout outside this repository; never invent limits or messages. Where Butler departs from Clawcode, the code says so in a comment and [STATUS.md](docs/plan/STATUS.md) lists it.

## How to pick up and finish a task

1. Open [docs/plan/TASKS.md](docs/plan/TASKS.md) and take a task whose Status is `Ready` (every Need merged). Tasks marked "decision pending" wait for the owner's answer in STATUS.md.
2. Read the task's lane doc in [docs/plan/lanes/](docs/plan/lanes/) (sections Design, Tasks, Tests) and the shared types in [docs/plan/CONTRACTS.md](docs/plan/CONTRACTS.md).
3. Change only files your lane owns (the lane doc's "Where the code goes" table). `UseCaseConfig.kt` and `application.yml` are shared and append-only. Each port has its own config class in `secondary-adaptor/.../config/`; a lane swaps its stand-in there and deletes the stand-in.
4. Need a contract change (a type or port in `core` that other lanes use)? Make it additive (a new optional parameter or type) and keep it in its own commit, before the code that uses it.
5. Run `./gradlew build`. Fix everything; do not skip or delete existing tests.
6. Commit one task per commit: `feat: <what users or callers get> (<task id>)`. In the same commit, set the task's Status in TASKS.md to `Merged` and note any decision or departure in STATUS.md.
7. Do not turn on `butler.tools.enabled`; lane J does that at gate M1.

## Where to stop and ask

Stop and ask the owner, rather than guessing, when: a task needs a decision listed as open in STATUS.md; a contract must change in a way that is not additive; a lane doc and the code disagree; or the build fails in code your task did not touch.
