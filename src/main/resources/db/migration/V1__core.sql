create table claims (
  id varchar(36) primary key,
  employee_id varchar(80) not null,
  category varchar(30) not null,
  amount_cents bigint not null,
  merchant varchar(200),
  expense_date date,
  receipt_hash varchar(64),
  state varchar(30) not null,
  idempotency_key varchar(120) not null unique,
  created_at timestamp not null,
  updated_at timestamp not null
);
create index idx_claims_receipt on claims(receipt_hash);
create table audit_events (
  id varchar(36) primary key,
  claim_id varchar(36) not null,
  actor varchar(80) not null,
  action varchar(80) not null,
  detail varchar(1000),
  occurred_at timestamp not null,
  constraint fk_audit_claim foreign key(claim_id) references claims(id)
);
