-- One row per magic link issued and not yet redeemed (spec 0227), by its token's SHA-256.
create table surefold_magic_links (
    token_hash bytea       primary key,
    user_id    text        not null,
    expires_at timestamptz not null
);
create index surefold_magic_links_expires on surefold_magic_links (expires_at);
