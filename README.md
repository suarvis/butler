# Butler

Butler is a rewrite of [Claw Code](https://github.com/ultraworkers/claw-code) (the `claw` terminal coding agent) in **Kotlin + Spring Boot**, built on a strict clean architecture. It runs as a single local process: the CLI, the agent loop and the tools live in one JVM, and the tools act on the folder you run it from.

> **Status:** slices 0–3 of the rewrite. In place: project skeleton and architecture guards, saved **sessions** (file and PostgreSQL stores), **conversations** (one-shot prompts, streaming, a basic interactive REPL) with **Anthropic, OpenAI and xAI (Responses API) and OpenAI-compatible providers** (OpenRouter, Ollama, any Chat Completions server), **switching provider mid-session**, and **monthly budgets with rate-limit-aware routing** (asks before going over; offers fallback models). Tools, permission approvals and MCP come in the next slices.

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

export ANTHROPIC_API_KEY=sk-ant-...        # your own keys; Butler never stores them
export OPENAI_API_KEY=sk-...                # only for the providers you use

cd ~/dev/some-project
butler "explain how this project is built"  # one-shot prompt, streamed
butler --model opus prompt "review the error handling"
butler --model gpt prompt "same question, other provider"
butler --model ollama/qwen3-coder "runs on your local Ollama, no key needed"
git diff | butler prompt --stdin "write a commit message for this"
butler --output-format json prompt "summarise README.md"
butler prompt --session latest "and what about the tests?"
butler                                      # interactive REPL (/help, /model, /status, /exit)
                                            #   /model gpt switches provider; the conversation goes along
butler --resume latest                      # REPL continuing the latest session
butler usage                                # this month's spend per provider and model, and budgets
butler session list
butler status
butler --output-format json session list
butler --resume latest /status /export notes.md
butler session fork --session latest experiment
butler session delete session-1760600000000-0 --force
butler export --output conversation.md
```

The commands, flags, JSON envelopes, error kinds and exit codes follow `claw` (exit `0` ok, `1` runtime error, `2` usage error; with `--output-format json` even errors are a single JSON object on stdout).

### Models and providers

A model is named by an alias (`opus`, `sonnet`, `haiku`, `gpt`, `grok`), a full id (`claude-sonnet-4-6`, `gpt-5.5`) or `provider/model` (`ollama/llama3.2`, `openrouter/qwen/qwen3-coder`). The provider is chosen from the model's catalog entry, then the `provider/` prefix, then each provider's `model-prefixes` (`claude` → anthropic, `gpt-` → openai, `grok` → xai). A model no provider serves is rejected (`unknown_model`) rather than sent somewhere by guesswork.

| Provider | Dialect | Key | Base URL (override) |
|---|---|---|---|
| `anthropic` | Anthropic Messages | `ANTHROPIC_API_KEY` | `ANTHROPIC_BASE_URL` |
| `openai` | Responses API | `OPENAI_API_KEY` | `OPENAI_BASE_URL` |
| `xai` | Responses API | `XAI_API_KEY` | `XAI_BASE_URL` |
| `openrouter` | Chat Completions | `OPENROUTER_API_KEY` | `OPENROUTER_BASE_URL` |
| `ollama` | Chat Completions | none | `OLLAMA_BASE_URL` (`http://localhost:11434/v1`) |

Each provider's `kind` picks its API dialect: `anthropic`, `openai-responses` (the OpenAI Responses API, which OpenAI and xAI recommend and which OpenAI's newest models need for tool calling) or `openai-compatible` (Chat Completions, for OpenRouter, Ollama and most other servers). Responses requests are sent with `store: false`: Butler keeps the conversation itself. Another server (vLLM, LM Studio, a company gateway) is one more `butler.providers.<id>` entry in configuration; no code. A session can change model and provider at any point: each request sends the stored conversation in a form the new provider accepts (provider-specific thinking is dropped, tool history the request cannot replay becomes text), while the saved session keeps everything.

### Budgets and rate limits

With your own keys there is no prepaid balance, so Butler keeps a usage ledger (every model call, its tokens and estimated USD cost) and checks it against monthly budgets you set per provider and, optionally, per model:

```yaml
butler:
  budgets:
    providers:
      anthropic: { monthly-usd: 50 }
      openai: { monthly-usd: 20, monthly-tokens: 20000000 }
    models:
      claude-opus-4-7: { monthly-usd: 30 }
```

Before each call Butler adds this month's spend to the estimated input cost of the request. If that passes a limit, or the provider answered 429 recently (the cooldown is remembered across runs), the turn asks: continue over budget, switch to one of your `butler.models.fallbacks` that has a key and is within budget, or stop. "Continue" covers the rest of the REPL session. Scripts and piped runs are never asked: they stop with `budget_exceeded` (exit 1) unless you pass `--allow-over-budget`, which prints a warning instead. Months follow your system time zone; the answer's own cost is only known afterwards, so one answer can go slightly past a limit. `butler usage` (or `/usage`) shows where you stand.

### Session storage

| Store | Select with | Where |
|---|---|---|
| Files (default) | `butler.persistence.store=file` | `<workspace>/.butler/sessions/<id>.jsonl`, in Claw Code's session format v3; usage ledger in `~/.butler/usage/<yyyy-MM>.jsonl`, cooldowns in `~/.butler/rate-limits.json` |
| PostgreSQL | `BUTLER_PERSISTENCE_STORE=postgresql` | `butler_session*`, `butler_usage` and `butler_rate_limit` tables, migrated by Flyway on first use |

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
| `butler.persistence.file.user-directory` | `BUTLER_PERSISTENCE_FILE_USERDIRECTORY` | `~/.butler` |
| `butler.persistence.postgresql.url` | `BUTLER_PERSISTENCE_POSTGRESQL_URL` | `jdbc:postgresql://localhost:5432/butler` |
| `butler.persistence.postgresql.username` | `BUTLER_PERSISTENCE_POSTGRESQL_USERNAME` | `butler` |
| `butler.persistence.postgresql.password` | `BUTLER_PERSISTENCE_POSTGRESQL_PASSWORD` | *(none)* |
| `butler.providers.<id>.api-key` | `<ID>_API_KEY`, e.g. `OPENAI_API_KEY` | *(none)* |
| `butler.providers.<id>.base-url` | `<ID>_BASE_URL` | per provider, see above |
| `butler.providers.<id>.kind`, `model-prefixes`, `max-tokens-parameter`, `stream-usage`, `api-key-required` | — | in `application.yml` |
| `butler.models.default` | `BUTLER_MODELS_DEFAULT` | `sonnet` |
| `butler.models.aliases.*`, `butler.models.profiles.*` | — | aliases, providers, output limits, context windows and prices in `application.yml` |
| `butler.models.fallbacks` | — | `sonnet, gpt, grok` |
| `butler.budgets.providers.<id>.monthly-usd` / `monthly-tokens` | e.g. `BUTLER_BUDGETS_PROVIDERS_ANTHROPIC_MONTHLYUSD` | *(no limits)* |
| `butler.budgets.models.<id>.monthly-usd` / `monthly-tokens` | — | *(no limits)* |
| — | `BUTLER_OUTPUT_FORMAT` | `text` |
| — | `BUTLER_LOG_LEVEL` | `WARN` (logs go to stderr) |

No secrets live in source. Provider API keys are your own and come from environment variables.
