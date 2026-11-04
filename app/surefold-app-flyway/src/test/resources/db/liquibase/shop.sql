--liquibase formatted sql

--changeset shop:0014-orders
create table orders (
    id    integer primary key,
    total integer
);
