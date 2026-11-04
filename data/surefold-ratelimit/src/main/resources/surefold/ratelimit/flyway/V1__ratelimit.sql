-- One token bucket per key (spec 0226): what it held when last drawn on, and when. A take refills and draws in one
-- statement on its row, so every instance draws on the same bucket.
create table surefold_rate_limits (
    key         text             primary key,
    tokens      double precision not null,
    refilled_at timestamptz      not null
);
