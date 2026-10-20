-- The usage ledger behind budgets: one row per model call, for every workspace of the user.
create table butler_usage (
    id                          bigserial     primary key,
    recorded_at                 timestamptz   not null,
    provider                    varchar(64)   not null,
    model                       varchar(200)  not null,
    input_tokens                bigint        not null,
    output_tokens               bigint        not null,
    cache_creation_input_tokens bigint        not null,
    cache_read_input_tokens     bigint        not null,
    -- Estimated USD; null when the model has no price (local or unknown models).
    cost_usd                    numeric(20, 10)
);

create index butler_usage_recorded_at_idx on butler_usage (recorded_at);

-- Providers that answered 429, and until when Butler should leave them alone.
create table butler_rate_limit (
    provider       varchar(64) primary key,
    cooldown_until timestamptz not null
);
