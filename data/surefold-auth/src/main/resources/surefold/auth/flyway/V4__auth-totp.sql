-- One TOTP secret per user (spec 0228), AES-GCM encrypted under a key the database never sees, and the last step a
-- code was accepted for, so no code is accepted twice.
create table surefold_totp (
    user_id   text    primary key,
    secret    bytea   not null,
    confirmed boolean not null,
    last_step bigint
);
