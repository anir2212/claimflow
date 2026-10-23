-- Optimistic-lock version for claims. State changes also take a row lock (SELECT ... FOR UPDATE);
-- the version makes any update based on a stale read fail instead of silently overwriting.
alter table claims add column version bigint not null default 0;
