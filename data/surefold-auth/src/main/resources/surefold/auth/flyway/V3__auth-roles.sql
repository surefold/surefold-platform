-- Roles granted to a user in a tenant, or in every tenant when tenant_id is null, and what each allows (spec 0227).
create table surefold_role_grants (
    user_id   text not null,
    tenant_id text,
    role      text not null,
    unique nulls not distinct (user_id, tenant_id, role)
);
create table surefold_role_permissions (
    role       text not null,
    permission text not null,
    primary key (role, permission)
);
