-- MySQL's bare TIMESTAMP stores whole seconds. Sequential audit writes in one
-- second can then be returned in an arbitrary order. Keep Java Instant's
-- microsecond precision so chronological queries are meaningful.
alter table audit_events modify column occurred_at timestamp(6) not null;
