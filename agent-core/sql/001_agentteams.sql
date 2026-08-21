-- AgentTeams P1-P4 authoritative state (PostgreSQL compatible).
create table if not exists run (
  run_id uuid primary key,
  client_code text not null,
  status text not null check (status in ('RUNNING','WAITING_HUMAN','SUCCEEDED','FAILED','ABORTED')),
  current_phase text not null check (current_phase in ('P1','P2','P3','P3B','P3C','P4')),
  build_path text check (build_path in ('P3','P3B','P3C','EARLY_EXIT')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists artifact (
  artifact_id uuid primary key,
  run_id uuid not null references run(run_id),
  client_code text not null,
  kind text not null,
  version int not null check (version > 0),
  payload jsonb not null,
  written_by text not null,
  created_at timestamptz not null default now(),
  unique (run_id, kind, version)
);
create index if not exists artifact_run_kind_idx on artifact(run_id, kind, version desc);

create table if not exists agent_blueprint (
  blueprint_id text primary key,
  client_code text not null,
  runtime_agent_id text not null,
  version int not null,
  status text not null check (status in ('DRAFT','STAGED','PUBLISHED','RETIRED')),
  payload jsonb not null,
  source_run_id uuid references run(run_id),
  written_by text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (client_code, runtime_agent_id, version)
);
create index if not exists agent_blueprint_lookup_idx on agent_blueprint(client_code, status, updated_at desc);

create table if not exists agent_binding (
  client_code text not null,
  user_id text not null,
  runtime_agent_id text not null,
  blueprint_id text not null references agent_blueprint(blueprint_id),
  projected_version int,
  projected_at timestamptz,
  primary key (client_code, user_id, runtime_agent_id)
);

create table if not exists agentscope_skills (
  name text not null,
  version int not null,
  skill_md text not null,
  resources jsonb not null default '{}'::jsonb,
  industries text[] not null default '{}',
  scenarios text[] not null default '{}',
  active boolean not null default true,
  created_at timestamptz not null default now(),
  primary key (name, version)
);
create index if not exists agentscope_skills_tags_idx on agentscope_skills using gin(industries,scenarios);

-- Tenant identity is part of every cross-table reference. Single-column FKs alone would permit
-- a compromised worker to pair another tenant's opaque id with its own client_code.
do $$ begin
  alter table run add constraint run_id_client_unique unique (run_id, client_code);
exception when duplicate_object or duplicate_table then null; end $$;
do $$ begin
  alter table agent_blueprint add constraint blueprint_id_client_unique unique (blueprint_id, client_code);
exception when duplicate_object or duplicate_table then null; end $$;
do $$ begin
  alter table artifact add constraint artifact_run_tenant_fk foreign key (run_id, client_code) references run(run_id, client_code);
exception when duplicate_object or duplicate_table then null; end $$;
do $$ begin
  alter table agent_binding add constraint binding_blueprint_tenant_fk foreign key (blueprint_id, client_code) references agent_blueprint(blueprint_id, client_code);
exception when duplicate_object or duplicate_table then null; end $$;
do $$ begin
  alter table agent_blueprint add constraint blueprint_source_run_tenant_fk foreign key (source_run_id, client_code) references run(run_id, client_code);
exception when duplicate_object or duplicate_table then null; end $$;

-- Resolve the tenant from an opaque run_id without disabling RLS for normal table reads.
-- The function exposes only client_code for one UUID and pins search_path against hijacking.
do $$ begin create role chatflows_tenant_lookup nologin bypassrls; exception when duplicate_object then null; end $$;
alter role chatflows_tenant_lookup nologin bypassrls;
grant usage on schema public to chatflows_tenant_lookup;
grant select on run to chatflows_tenant_lookup;
create or replace function lookup_run_client(p_run_id uuid) returns text
language sql stable security definer set search_path=public,pg_temp as $$
  select client_code from public.run where run_id=p_run_id
$$;
alter function lookup_run_client(uuid) owner to chatflows_tenant_lookup;
revoke all on function lookup_run_client(uuid) from public;

-- Enforce the only legal forward transitions. Rollback publishes an older STAGED version;
-- it never mutates PUBLISHED directly back to STAGED.
create or replace function enforce_blueprint_status_transition() returns trigger language plpgsql as $$
begin
  if old.status = new.status then return new; end if;
  if not ((old.status='DRAFT' and new.status='STAGED') or
          (old.status='STAGED' and new.status='PUBLISHED') or
          (old.status='PUBLISHED' and new.status='RETIRED') or
          (old.status='RETIRED' and new.status='PUBLISHED')) then
    raise exception 'illegal blueprint status transition: % -> %', old.status, new.status;
  end if;
  return new;
end $$;
drop trigger if exists agent_blueprint_status_guard on agent_blueprint;
create trigger agent_blueprint_status_guard before update of status on agent_blueprint
for each row execute function enforce_blueprint_status_transition();

-- Enforce Blueprint authority even if application code is compromised. Runtime never reaches
-- this trigger because it has no UPDATE grant on agent_blueprint.
create or replace function enforce_blueprint_role_write() returns trigger language plpgsql as $$
begin
  if tg_op='INSERT' then
    if current_user <> 'worker_p3c' or new.status <> 'DRAFT' or new.written_by <> 'blueprint-compose' then
      raise exception 'only worker_p3c may insert DRAFT Blueprint';
    end if;
    if new.payload->>'clientCode' is distinct from new.client_code or new.payload->>'runtimeAgentId' is distinct from new.runtime_agent_id or (new.payload->>'version')::int is distinct from new.version then
      raise exception 'Blueprint payload identity mismatch';
    end if;
    if exists(select 1 from agent_blueprint b where b.client_code=new.client_code and b.runtime_agent_id=new.runtime_agent_id and b.payload->'runtime'->>'isolationScope' is distinct from new.payload->'runtime'->>'isolationScope') then
      raise exception 'runtime isolationScope is immutable after first Blueprint';
    end if;
    return new;
  end if;
  if new.blueprint_id<>old.blueprint_id or new.client_code<>old.client_code or new.runtime_agent_id<>old.runtime_agent_id or new.version<>old.version or new.payload<>old.payload or new.source_run_id is distinct from old.source_run_id or new.written_by<>old.written_by or new.created_at<>old.created_at then
    raise exception 'Blueprint content and identity are immutable after insert';
  end if;
  if current_user='worker_p3c' and not(old.status='DRAFT' and new.status='DRAFT') then
    raise exception 'worker_p3c may only edit DRAFT metadata';
  elsif current_user='worker_p4' and not(old.status='DRAFT' and new.status='STAGED') then
    raise exception 'worker_p4 illegal Blueprint transition';
  elsif current_user='blueprint_admin' and not((old.status='STAGED' and new.status='PUBLISHED') or (old.status='PUBLISHED' and new.status='RETIRED') or (old.status='RETIRED' and new.status='PUBLISHED')) then
    raise exception 'blueprint_admin illegal Blueprint transition';
  elsif current_user not in ('worker_p3c','worker_p4','blueprint_admin') then
    raise exception 'role may not mutate Blueprint';
  end if;
  return new;
end $$;
drop trigger if exists agent_blueprint_role_guard on agent_blueprint;
create trigger agent_blueprint_role_guard before insert or update on agent_blueprint
for each row execute function enforce_blueprint_role_write();

-- A7b: role names are deployment contracts. GRANTs are intentionally explicit per kind.
do $$ begin create role chatflows_leader nologin; exception when duplicate_object then null; end $$;
do $$ begin create role worker_p1 nologin; exception when duplicate_object then null; end $$;
do $$ begin create role worker_p2 nologin; exception when duplicate_object then null; end $$;
do $$ begin create role worker_p3 nologin; exception when duplicate_object then null; end $$;
do $$ begin create role worker_p3b nologin; exception when duplicate_object then null; end $$;
do $$ begin create role worker_p3c nologin; exception when duplicate_object then null; end $$;
do $$ begin create role worker_p4 nologin; exception when duplicate_object then null; end $$;
do $$ begin create role blueprint_admin nologin; exception when duplicate_object then null; end $$;
do $$ begin create role agent_runtime nologin; exception when duplicate_object then null; end $$;
do $$ begin create role chatflows_app nologin; exception when duplicate_object then null; end $$;

-- The actual DATABASE_URL login is granted chatflows_app by deployment automation. The service
-- SET LOCAL ROLEs to exactly one worker role per write; indirect membership enables that switch.
grant worker_p1, worker_p2, worker_p3, worker_p3b, worker_p3c, worker_p4, blueprint_admin to chatflows_app;
grant usage on schema public to chatflows_app, agent_runtime, chatflows_leader, worker_p1, worker_p2, worker_p3, worker_p3b, worker_p3c, worker_p4, blueprint_admin;
grant select, insert, update on run to chatflows_app;
grant select on artifact, agent_blueprint, agent_binding to chatflows_app;

grant select on run, artifact, agent_blueprint, agent_binding to chatflows_leader;
grant select on run, artifact to worker_p1, worker_p2, worker_p3, worker_p3b, worker_p3c, worker_p4;
grant insert on artifact to worker_p1, worker_p2, worker_p3, worker_p3b, worker_p3c, worker_p4;
grant select, insert, update on agent_blueprint to worker_p3c;
grant select, update on agent_blueprint to worker_p4;
grant select, update on agent_blueprint to blueprint_admin;
grant select, insert, update on agent_binding to worker_p4;
grant select on agentscope_skills to worker_p3c;
grant select on agent_blueprint, agent_binding, agentscope_skills to agent_runtime;
grant update (projected_version, projected_at) on agent_binding to agent_runtime;
grant execute on function lookup_run_client(uuid) to chatflows_app;

alter table run enable row level security;
alter table run force row level security;
drop policy if exists run_tenant_isolation on run;
create policy run_tenant_isolation on run using (client_code = current_setting('app.client_code', true)) with check (client_code = current_setting('app.client_code', true));
alter table agent_blueprint enable row level security;
alter table agent_blueprint force row level security;
drop policy if exists blueprint_tenant_isolation on agent_blueprint;
create policy blueprint_tenant_isolation on agent_blueprint using (client_code = current_setting('app.client_code', true)) with check (client_code = current_setting('app.client_code', true));
alter table agent_binding enable row level security;
alter table agent_binding force row level security;
drop policy if exists binding_tenant_isolation on agent_binding;
create policy binding_tenant_isolation on agent_binding using (client_code = current_setting('app.client_code', true)) with check (client_code = current_setting('app.client_code', true));

alter table artifact enable row level security;
alter table artifact force row level security;
drop policy if exists artifact_tenant_isolation on artifact;
create policy artifact_tenant_isolation on artifact for select using (client_code = current_setting('app.client_code', true));
drop policy if exists artifact_worker_write on artifact;
create policy artifact_worker_write on artifact for insert with check (
  client_code = current_setting('app.client_code', true) and
  ((current_user='worker_p1' and kind in ('wizard_state','triage')) or
   (current_user='worker_p2' and kind='match_result') or
   (current_user='worker_p3' and kind in ('guidance','personalized_package','flow_check')) or
   (current_user='worker_p3b' and kind in ('flow_yaml','flow_check')) or
   (current_user='worker_p3c' and kind in ('blueprint','blueprint_draft','blueprint_check','expert_dispatch','expert_result')) or
   (current_user='worker_p4' and kind in ('import_result','dry_run','approval','evidence')) or
   (current_user='blueprint_admin' and kind='evidence' and written_by='blueprint-admin'))
);
grant select, insert on artifact to blueprint_admin;
