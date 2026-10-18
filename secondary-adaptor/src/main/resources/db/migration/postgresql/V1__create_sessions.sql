-- Sessions are scoped per workspace, like the file store. Message content blocks are stored as
-- JSONB in the same shape as the JSONL records, so the two stores stay easy to compare.

create table butler_session (
    id                              varchar(128) primary key,
    workspace_root                  text         not null,
    model                           varchar(200),
    created_at                      timestamptz  not null,
    updated_at                      timestamptz  not null,
    fork_parent_session_id          varchar(128),
    fork_branch_name                varchar(100),
    compaction_count                integer,
    compaction_removed_message_count integer,
    compaction_summary              text
);

create index butler_session_workspace_updated_idx
    on butler_session (workspace_root, updated_at desc, id desc);

create table butler_session_message (
    session_id varchar(128) not null references butler_session (id) on delete cascade,
    ordinal    integer      not null,
    role       varchar(16)  not null,
    blocks     jsonb        not null,
    usage      jsonb,
    primary key (session_id, ordinal)
);

create table butler_session_prompt (
    session_id varchar(128) not null references butler_session (id) on delete cascade,
    ordinal    integer      not null,
    entered_at timestamptz  not null,
    text       text         not null,
    primary key (session_id, ordinal)
);
