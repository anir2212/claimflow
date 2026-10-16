create table extraction_jobs (
  claim_id varchar(36) primary key,
  receipt_text varchar(10000) not null,
  input_hash varchar(64) not null,
  status varchar(20) not null,
  attempts integer not null,
  available_at timestamp(6) not null,
  last_error varchar(500),
  constraint fk_extraction_claim foreign key(claim_id) references claims(id)
);
create index idx_extraction_due on extraction_jobs(status, available_at);
