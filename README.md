# Butler

Butler is a rewrite of [Claw Code](https://github.com/ultraworkers/claw-code) (the `claw` terminal coding agent) in **Kotlin + Spring Boot**, built on a strict clean architecture. It runs as a single local process: the CLI, the agent loop and the tools live in one JVM, and the tools act on the folder you run it from.

> **Status:** slices 0–2 of the rewrite. In place: project skeleton and architecture guards, saved **sessions** (file and PostgreSQL stores), and **conversations with Anthropic models** (one-shot prompts, streaming, a basic interactive REPL). Model routing and budgets, the OpenAI-compatible provider, tools, approvals and MCP come in the next slices.

## Architecture

Dependencies point inward. Business logic never depends on infrastructure.

```
api-primary-adaptor ──► core ◄── secondary-adaptor
        └────────── runtimeOnly ─────────┘   (composition only, never compile-time)
```

| Module | Holds | May depend on |
|---|---|---|
| `core` | `domain` (entities, value objects, `generic` ids/types/`Page`), `gateway` (ports), `usecase` | Kotlin stdlib and the JDK only |
| `api-primary-adaptor` | Spring Boot entry point, use-case wiring (`config`), CLI (`cli/command`, `dto`, `mapper`, `render`, `exception`), `security/authorization` rules | `core` |
| `secondary-adaptor` | `gateway/impl`, `persistence/file` and `persistence/postgresql` (`entity`, `mapper`, `repository`, `support`), `config` | `core` |

Each layer has its own model with explicit mappers: domain `Session` ↔ CLI DTOs (`SessionCliMapper`) and domain ↔ persistence records (`SessionFilePersistenceMapper`, `SessionPostgresqlPersistenceMapper`).

Two independent guards enforce the rule:

- `./gradlew :core:verifyCoreIsFrameworkFree` fails if `core`'s runtime classpath contains anything except the Kotlin standard library.
- `ArchitectureTest` (ArchUnit, in `api-primary-adaptor`) checks dependency direction, naming (`*UseCase`, `*GatewayImpl`, `*PersistenceMapper`, `*CliMapper`, `*Rule`), that gateways are interfaces, and that DTOs and persistence models never expose domain types.

## Requirements

- A JDK 17 or newer to run Gradle. The build compiles and tests on a JDK 21 toolchain, which Gradle downloads automatically if it is not installed.
- JDK 21 or newer to run the packaged `butler.jar`
- Docker, only for the PostgreSQL store and its integration tests

## Build and test

```bash
./gradlew build                # compile, unit + adaptor tests, ArchUnit, core classpath guard
./gradlew architectureCheck    # only the dependency-rule guards
./gradlew :core:test           # domain and use-case tests (no infrastructure)
```

PostgreSQL tests use Testcontainers and are skipped automatically when Docker is not running.

## Run

```bash
./gradlew :api-primary-adaptor:bootJar
alias butler='java -jar "$PWD/api-primary-adaptor/build/libs/butler.jar"'

export ANTHROPIC_API_KEY=sk-ant-...        # your own key; Butler never stores it

cd ~/dev/some-project
butler "explain how this project is built"  # one-shot prompt, streamed
butler --model opus prompt "review the error handling"
git diff | butler prompt --stdin "write a commit message for this"
butler --output-format json prompt "summarise README.md"
butler prompt --session latest "and what about the tests?"
butler                                      # interactive REPL (/help, /model, /status, /exit)
butler --resume latest                      # REPL continuing the latest session
butler session list
butler status
butler --output-format json session list
butler --resume latest /status /export notes.md
butler session fork --session latest experiment
butler session delete session-1760600000000-0 --force
butler export --output conversation.md
```

The commands, flags, JSON envelopes, error kinds and exit codes follow `claw` (exit `0` ok, `1` runtime error, `2` usage error; with `--output-format json` even errors are a single JSON object on stdout).

### Session storage

| Store | Select with | Where |
|---|---|---|
| Files (default) | `butler.persistence.store=file` | `<workspace>/.butler/sessions/<id>.jsonl`, in Claw Code's session format v3 |
| PostgreSQL | `BUTLER_PERSISTENCE_STORE=postgresql` | `butler_session*` tables, migrated by Flyway on first use |

```bash
docker compose up -d postgres
export BUTLER_PERSISTENCE_STORE=postgresql
export BUTLER_PERSISTENCE_POSTGRESQL_PASSWORD=butler-local
butler session list
```

Or run the CLI itself in a container against the current folder:

```bash
docker compose run --rm butler session list
```

### Configuration

Spring configuration properties, overridable by environment variables:

| Property | Environment variable | Default |
|---|---|---|
| `butler.persistence.store` | `BUTLER_PERSISTENCE_STORE` | `file` |
| `butler.persistence.file.directory` | `BUTLER_PERSISTENCE_FILE_DIRECTORY` | `.butler/sessions` |
| `butler.persistence.postgresql.url` | `BUTLER_PERSISTENCE_POSTGRESQL_URL` | `jdbc:postgresql://localhost:5432/butler` |
| `butler.persistence.postgresql.username` | `BUTLER_PERSISTENCE_POSTGRESQL_USERNAME` | `butler` |
| `butler.persistence.postgresql.password` | `BUTLER_PERSISTENCE_POSTGRESQL_PASSWORD` | *(none)* |
| `butler.providers.anthropic.api-key` | `ANTHROPIC_API_KEY` | *(none)* |
| `butler.providers.anthropic.base-url` | `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` |
| `butler.models.default` | `BUTLER_MODELS_DEFAULT` | `sonnet` |
| `butler.models.aliases.*`, `butler.models.profiles.*` | — | Anthropic aliases, output limits and prices in `application.yml` |
| — | `BUTLER_OUTPUT_FORMAT` | `text` |
| — | `BUTLER_LOG_LEVEL` | `WARN` (logs go to stderr) |

No secrets live in source. Provider API keys are your own and come from environment variables.
