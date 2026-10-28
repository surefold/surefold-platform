--liquibase formatted sql

--changeset example:system-functions
-- Owns the functions and logs in as no one. It holds only what they read, and a policy lets it read across tenants.
create role booking_definer nologin;
grant select on bookings to booking_definer;
create policy booking_definer_reads on bookings for select to booking_definer using (true);

-- The role SystemFunctions runs on: no table grants, only EXECUTE on the functions below.
create role booking_system login password 'change-me';
create schema sys authorization booking_definer;
grant usage on schema sys to booking_system;

create function sys.booking_count(tenant text) returns bigint
    language sql stable security definer set search_path = public, pg_temp
    as $$ select count(*) from bookings where tenant_id = tenant $$;
alter function sys.booking_count(text) owner to booking_definer;
revoke execute on function sys.booking_count(text) from public;
grant execute on function sys.booking_count(text) to booking_system;

grant execute on function surefold_tenancy_audit_write(text, text) to booking_system;
