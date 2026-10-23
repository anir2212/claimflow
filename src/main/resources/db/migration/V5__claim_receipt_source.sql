-- Records whether a claim's receipt arrived as typed text, an uploaded image, or not at all,
-- so every role that can see the claim can also see how its receipt was supplied.
-- Image bytes are still never stored; this column holds only the source label.
alter table claims add column receipt_source varchar(10);
update claims set receipt_source = 'TEXT'
  where receipt_hash is not null or id in (select claim_id from extraction_jobs);
update claims set receipt_source = 'NONE' where receipt_source is null;
alter table claims modify column receipt_source varchar(10) not null;
