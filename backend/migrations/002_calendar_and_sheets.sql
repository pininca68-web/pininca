-- Apply once AFTER ../schema.sql, as postgres. Existing data is preserved.
-- Adds a shared calendar and transactional Google Sheets delivery outbox.
-- Outbox records intentionally survive family/account deletion and retain
-- historical coordinates after sharing stops. The app must disclose this.
-- No Google credentials, Auth tokens or invitation codes belong in the outbox.
begin;

create table family_private.calendar_events (
    id uuid primary key default pg_catalog.gen_random_uuid(),
    family_id uuid not null references family_private.families(id) on delete cascade,
    title text not null check (pg_catalog.char_length(pg_catalog.btrim(title)) between 1 and 120),
    description text not null default '' check (pg_catalog.char_length(description) <= 4000),
    location text not null default '' check (pg_catalog.char_length(location) <= 300),
    starts_at timestamptz not null check (pg_catalog.isfinite(starts_at)),
    ends_at timestamptz not null check (pg_catalog.isfinite(ends_at)),
    timezone text not null default 'Asia/Taipei' check (pg_catalog.char_length(timezone) between 1 and 100),
    reminder_minutes integer not null default 15 check (reminder_minutes in (0, 5, 10, 15, 30, 60, 1440)),
    -- Historical authorship, deliberately no membership/Auth cascading FK.
    created_by uuid not null,
    created_at timestamptz not null default pg_catalog.clock_timestamp(),
    updated_at timestamptz not null default pg_catalog.clock_timestamp(),
    version integer not null default 1 check (version > 0),
    check (ends_at > starts_at)
);
create index calendar_events_family_start_idx on family_private.calendar_events(family_id, starts_at, id);

create table family_private.app_settings (
    user_id uuid primary key references family_private.members(user_id) on delete cascade,
    family_id uuid not null references family_private.families(id) on delete cascade,
    interval_minutes integer not null default 60 check (interval_minutes in (30, 60, 120, 180, 240)),
    reminders_enabled boolean not null default false,
    placement text not null default 'TOP' check (placement in ('TOP', 'BOTTOM')),
    calendar_reminders_enabled boolean not null default false,
    updated_at timestamptz not null default pg_catalog.clock_timestamp()
);

create table family_private.sheet_outbox (
    event_id uuid primary key default pg_catalog.gen_random_uuid(),
    -- No family/user FK: business history must survive cascading deletion.
    family_id uuid not null,
    user_id uuid,
    entity_type text not null check (entity_type in ('family', 'member', 'location', 'calendar', 'settings')),
    operation text not null check (operation in ('INSERT', 'UPDATE', 'DELETE')),
    entity_id text not null,
    occurred_at timestamptz not null default pg_catalog.clock_timestamp(),
    payload jsonb not null check (pg_catalog.jsonb_typeof(payload) = 'object'),
    lease_id uuid,
    lease_until timestamptz,
    delivered_at timestamptz,
    last_error text,
    next_attempt_at timestamptz not null default pg_catalog.clock_timestamp(),
    attempt_count integer not null default 0 check (attempt_count >= 0)
);
create index sheet_outbox_pending_idx on family_private.sheet_outbox(family_id, next_attempt_at, occurred_at, event_id)
    where delivered_at is null;
create index sheet_outbox_labels_idx on family_private.sheet_outbox(family_id, entity_type, entity_id, occurred_at desc);

-- One family may have only one in-flight batch. This also covers deleted families.
create table family_private.sheet_family_leases (
    family_id uuid primary key,
    lease_id uuid,
    lease_until timestamptz
);

alter table family_private.calendar_events enable row level security;
alter table family_private.app_settings enable row level security;
alter table family_private.sheet_outbox enable row level security;
alter table family_private.sheet_family_leases enable row level security;
revoke all on table family_private.calendar_events, family_private.app_settings,
    family_private.sheet_outbox, family_private.sheet_family_leases from public, anon, authenticated, service_role;

-- Persist the location's family before membership deletion so its deletion audit
-- can always be attributed correctly, including Auth/FK cascades.
alter table family_private.locations add column family_id uuid;
update family_private.locations as l set family_id = m.family_id
from family_private.members as m where m.user_id = l.user_id;
alter table family_private.locations alter column family_id set not null;
create function family_private.tag_location_family()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_family_id uuid;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = new.user_id;
    if v_family_id is null then
        raise exception using errcode = '42501', message = '請先建立或加入家庭。';
    end if;
    new.family_id := v_family_id;
    return new;
end;
$$;
create trigger tag_location_family before insert or update on family_private.locations
for each row execute function family_private.tag_location_family();

create function public.calendar_list(p_from timestamptz, p_to timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_count integer;
    v_events jsonb;
begin
    if p_from is null or p_to is null or not pg_catalog.isfinite(p_from) or not pg_catalog.isfinite(p_to)
       or p_to <= p_from or p_to - p_from > interval '400 days' then
        raise exception using errcode = '22023', message = '請選擇有效的日期範圍，最多可查詢 400 天。';
    end if;
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid for share;
    if not found then
        return pg_catalog.jsonb_build_object('family_id', null, 'events', '[]'::jsonb, 'server_time', pg_catalog.clock_timestamp());
    end if;
    -- Count and aggregate the same snapshot, including the 501st row. Never
    -- silently omit events when the requested range contains more than 500.
    select pg_catalog.count(*)::integer,
           coalesce(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(e) order by e.starts_at, e.id), '[]'::jsonb)
    into v_count, v_events
    from (select c.* from family_private.calendar_events as c
          where c.family_id = v_family_id and c.starts_at < p_to and c.ends_at > p_from
          order by c.starts_at, c.id limit 501) as e;
    if v_count > 500 then
        raise exception using errcode = '22023', message = '此範圍超過 500 筆行程，請縮小日期範圍後再查詢。';
    end if;
    return pg_catalog.jsonb_build_object('family_id', v_family_id, 'events', v_events, 'server_time', pg_catalog.clock_timestamp());
end;
$$;

create function public.calendar_save(
    p_id uuid, p_title text, p_description text, p_location text,
    p_starts_at timestamptz, p_ends_at timestamptz, p_timezone text,
    p_reminder_minutes integer, p_expected_version integer,
    p_expected_family_id uuid default null
)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_title text := pg_catalog.btrim(p_title);
    v_description text := coalesce(p_description, '');
    v_location text := pg_catalog.btrim(coalesce(p_location, ''));
    v_timezone text := coalesce(nullif(pg_catalog.btrim(p_timezone), ''), 'Asia/Taipei');
    v_event family_private.calendar_events%rowtype;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid for share;
    if not found then
        raise exception using errcode = '42501', message = '請先建立或加入家庭。';
    end if;
    if p_expected_family_id is not null and p_expected_family_id <> v_family_id then
        raise exception using errcode = '42501', message = '目前家庭已變更，請重新開啟行事曆。';
    end if;
    if v_title is null or pg_catalog.char_length(v_title) not between 1 and 120
       or pg_catalog.char_length(v_description) > 4000 or pg_catalog.char_length(v_location) > 300
       or p_starts_at is null or p_ends_at is null
       or not pg_catalog.isfinite(p_starts_at) or not pg_catalog.isfinite(p_ends_at)
       or p_ends_at <= p_starts_at
       or pg_catalog.char_length(v_timezone) > 100
       or not exists (select 1 from pg_catalog.pg_timezone_names as z where z.name = v_timezone)
       or p_reminder_minutes is null or p_reminder_minutes not in (0, 5, 10, 15, 30, 60, 1440) then
        raise exception using errcode = '22023', message = '行程資料無效，請檢查標題、起訖時間、時區與提醒時間。';
    end if;
    if p_id is null then
        if p_expected_version is not null then
            raise exception using errcode = '22023', message = '新增行程不需指定版本，請重新開啟新增畫面。';
        end if;
        insert into family_private.calendar_events
            (family_id, title, description, location, starts_at, ends_at, timezone, reminder_minutes, created_by)
        values (v_family_id, v_title, v_description, v_location, p_starts_at, p_ends_at,
                v_timezone, p_reminder_minutes, v_uid) returning * into v_event;
    else
        select c.* into v_event from family_private.calendar_events as c
        where c.id = p_id and c.family_id = v_family_id for update;
        if not found then
            raise exception using errcode = '42501', message = '找不到此行程，或您已不在此家庭。';
        end if;
        if p_expected_version is null or p_expected_version <> v_event.version then
            raise exception using errcode = '40001', message = '這個行程已被家人修改，請重新整理後再儲存。';
        end if;
        update family_private.calendar_events as c set
            title = v_title, description = v_description, location = v_location,
            starts_at = p_starts_at, ends_at = p_ends_at, timezone = v_timezone,
            reminder_minutes = p_reminder_minutes, updated_at = pg_catalog.clock_timestamp(), version = c.version + 1
        where c.id = p_id returning * into v_event;
    end if;
    return pg_catalog.to_jsonb(v_event);
end;
$$;

create function public.calendar_delete(p_id uuid, p_expected_version integer)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_version integer;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid for share;
    if not found then raise exception using errcode = '42501', message = '請先建立或加入家庭。'; end if;
    select c.version into v_version from family_private.calendar_events as c
    where c.id = p_id and c.family_id = v_family_id for update;
    if not found then
        raise exception using errcode = '42501', message = '找不到此行程，或您已不在此家庭。';
    end if;
    if p_expected_version is null or p_expected_version <> v_version then
        raise exception using errcode = '40001', message = '這個行程已被家人修改，請重新整理後再刪除。';
    end if;
    delete from family_private.calendar_events as c where c.id = p_id;
end;
$$;

create function public.app_settings_save(
    p_interval_minutes integer, p_reminders_enabled boolean, p_placement text,
    p_calendar_reminders_enabled boolean, p_expected_family_id uuid default null
)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid for update;
    if not found then raise exception using errcode = '42501', message = '請先建立或加入家庭。'; end if;
    if p_expected_family_id is not null and p_expected_family_id <> v_family_id then
        raise exception using errcode = '42501', message = '目前家庭已變更，請重新開啟設定。';
    end if;
    if p_interval_minutes is null or p_interval_minutes not in (30, 60, 120, 180, 240)
       or p_reminders_enabled is null or p_calendar_reminders_enabled is null
       or p_placement is null or p_placement not in ('TOP', 'BOTTOM') then
        raise exception using errcode = '22023', message = '提醒設定無效，請重新選擇間隔與顯示位置。';
    end if;
    insert into family_private.app_settings as s
        (user_id, family_id, interval_minutes, reminders_enabled, placement, calendar_reminders_enabled)
    values (v_uid, v_family_id, p_interval_minutes, p_reminders_enabled, p_placement, p_calendar_reminders_enabled)
    on conflict (user_id) do update set family_id = excluded.family_id,
        interval_minutes = excluded.interval_minutes, reminders_enabled = excluded.reminders_enabled,
        placement = excluded.placement, calendar_reminders_enabled = excluded.calendar_reminders_enabled,
        updated_at = pg_catalog.clock_timestamp();
end;
$$;

-- Resolve labels from the live tables first, then the already captured audit
-- rows. BEFORE DELETE audit triggers preserve labels across cascading deletes.
create function family_private.enqueue_sheet_event(
    p_family_id uuid, p_actor_uid uuid, p_entity_type text, p_operation text,
    p_entity_id text, p_data jsonb, p_old jsonb, p_source text default 'trigger'
)
returns void language plpgsql security invoker set search_path = '' as $$
declare
    v_family_name text;
    v_subject_uid uuid;
    v_display_name text;
    v_actor_name text;
begin
    if p_entity_type = 'family' then v_family_name := p_data ->> 'name'; end if;
    if v_family_name is null then
        select f.name into v_family_name from family_private.families as f where f.id = p_family_id;
    end if;
    if v_family_name is null then
        select o.payload ->> 'family_name' into v_family_name from family_private.sheet_outbox as o
        where o.family_id = p_family_id and o.entity_type = 'family' order by o.occurred_at desc, o.event_id desc limit 1;
    end if;
    if p_entity_type in ('member', 'location', 'settings') then v_subject_uid := (p_data ->> 'user_id')::uuid;
    elsif p_entity_type = 'calendar' then v_subject_uid := (p_data ->> 'created_by')::uuid; end if;
    if p_entity_type = 'member' then v_display_name := p_data ->> 'display_name'; end if;
    if v_display_name is null and v_subject_uid is not null then
        select m.display_name into v_display_name from family_private.members as m
        where m.user_id = v_subject_uid and m.family_id = p_family_id;
        if not found then
            select o.payload ->> 'display_name' into v_display_name from family_private.sheet_outbox as o
            where o.family_id = p_family_id and o.entity_type = 'member' and o.entity_id = v_subject_uid::text
            order by o.occurred_at desc, o.event_id desc limit 1;
        end if;
    end if;
    if p_actor_uid = v_subject_uid then v_actor_name := v_display_name; end if;
    if v_actor_name is null and p_actor_uid is not null then
        select m.display_name into v_actor_name from family_private.members as m
        where m.user_id = p_actor_uid and m.family_id = p_family_id;
        if not found then
            select o.payload ->> 'display_name' into v_actor_name from family_private.sheet_outbox as o
            where o.family_id = p_family_id and o.entity_type = 'member' and o.entity_id = p_actor_uid::text
            order by o.occurred_at desc, o.event_id desc limit 1;
        end if;
    end if;
    if v_actor_name is null and p_actor_uid is not null then
        -- The creator's member row does not yet exist during family INSERT.
        v_actor_name := family_private.default_name(p_actor_uid);
    end if;
    insert into family_private.sheet_family_leases(family_id) values (p_family_id) on conflict do nothing;
    insert into family_private.sheet_outbox(family_id, user_id, entity_type, operation, entity_id, payload)
    values (p_family_id, p_actor_uid, p_entity_type, p_operation, p_entity_id,
        pg_catalog.jsonb_build_object('data', p_data, 'old', p_old, 'family_name', v_family_name,
            'display_name', v_display_name, 'actor_user_id', p_actor_uid,
            'actor_display_name', v_actor_name, 'source', p_source));
end;
$$;

create function family_private.audit_business_change()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
    v_data jsonb;
    v_old jsonb;
    v_entity_type text := tg_argv[0];
    v_family_id uuid;
    v_entity_id text;
begin
    if tg_op = 'UPDATE' then
        if new is not distinct from old then return new; end if;
    end if;
    if tg_op = 'DELETE' then v_data := pg_catalog.to_jsonb(old);
    else v_data := pg_catalog.to_jsonb(new); end if;
    if tg_op = 'UPDATE' then v_old := pg_catalog.to_jsonb(old); end if;
    if v_entity_type = 'family' then v_family_id := (v_data ->> 'id')::uuid;
    else v_family_id := (v_data ->> 'family_id')::uuid; end if;
    if v_entity_type in ('family', 'calendar') then v_entity_id := v_data ->> 'id';
    else v_entity_id := v_data ->> 'user_id'; end if;
    perform family_private.enqueue_sheet_event(v_family_id, auth.uid(), v_entity_type, tg_op, v_entity_id, v_data, v_old);
    if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

create trigger audit_family_write after insert or update on family_private.families
for each row execute function family_private.audit_business_change('family');
create trigger audit_family_delete before delete on family_private.families
for each row execute function family_private.audit_business_change('family');
create trigger audit_member_write after insert or update on family_private.members
for each row execute function family_private.audit_business_change('member');
create trigger audit_member_delete before delete on family_private.members
for each row execute function family_private.audit_business_change('member');
create trigger audit_location_write after insert or update on family_private.locations
for each row execute function family_private.audit_business_change('location');
create trigger audit_location_delete before delete on family_private.locations
for each row execute function family_private.audit_business_change('location');
create trigger audit_calendar_write after insert or update on family_private.calendar_events
for each row execute function family_private.audit_business_change('calendar');
create trigger audit_calendar_delete before delete on family_private.calendar_events
for each row execute function family_private.audit_business_change('calendar');
create trigger audit_settings_write after insert or update on family_private.app_settings
for each row execute function family_private.audit_business_change('settings');
create trigger audit_settings_delete before delete on family_private.app_settings
for each row execute function family_private.audit_business_change('settings');

-- Existing current records receive an explicit baseline snapshot, not invented
-- past history. Already deleted data cannot be reconstructed by this migration.
do $$
declare v_row record;
begin
    for v_row in select f.* from family_private.families as f loop
        perform family_private.enqueue_sheet_event(v_row.id, null, 'family', 'INSERT', v_row.id::text, pg_catalog.to_jsonb(v_row), null, 'migration_snapshot');
    end loop;
    for v_row in select m.* from family_private.members as m loop
        perform family_private.enqueue_sheet_event(v_row.family_id, null, 'member', 'INSERT', v_row.user_id::text, pg_catalog.to_jsonb(v_row), null, 'migration_snapshot');
    end loop;
    for v_row in select l.* from family_private.locations as l loop
        perform family_private.enqueue_sheet_event(v_row.family_id, null, 'location', 'INSERT', v_row.user_id::text, pg_catalog.to_jsonb(v_row), null, 'migration_snapshot');
    end loop;
end;
$$;

create function public.sheet_sync_status()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_result jsonb;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid for share;
    if not found then
        return pg_catalog.jsonb_build_object('queued', 0, 'failed', 0, 'last_delivered_at', null);
    end if;
    select pg_catalog.jsonb_build_object(
        'queued', pg_catalog.count(*) filter (where o.delivered_at is null),
        'failed', pg_catalog.count(*) filter (where o.delivered_at is null and o.last_error is not null),
        'last_delivered_at', pg_catalog.max(o.delivered_at)
    ) into v_result from family_private.sheet_outbox as o where o.family_id = v_family_id;
    return v_result;
end;
$$;

create function public.sheet_outbox_claim(p_limit integer default 50)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_now timestamptz := pg_catalog.clock_timestamp();
    v_family_id uuid;
    v_lease_id uuid := pg_catalog.gen_random_uuid();
    v_lease_until timestamptz := v_now + interval '5 minutes';
    v_claimed jsonb;
begin
    if p_limit is null or p_limit not between 1 and 50 then
        raise exception using errcode = '22023', message = '批次大小必須介於 1 與 50。';
    end if;
    select l.family_id into v_family_id from family_private.sheet_family_leases as l
    where (l.lease_until is null or l.lease_until <= v_now)
      and exists (select 1 from family_private.sheet_outbox as o where o.family_id = l.family_id
          and o.delivered_at is null and o.next_attempt_at <= v_now
          and (o.lease_until is null or o.lease_until <= v_now))
    order by (select pg_catalog.min(o.occurred_at) from family_private.sheet_outbox as o
              where o.family_id = l.family_id and o.delivered_at is null and o.next_attempt_at <= v_now
                and (o.lease_until is null or o.lease_until <= v_now)), l.family_id
    limit 1 for update of l skip locked;
    if not found then return '[]'::jsonb; end if;

    update family_private.sheet_family_leases as l set lease_id = v_lease_id, lease_until = v_lease_until
    where l.family_id = v_family_id;
    with pending as (
        select o.event_id from family_private.sheet_outbox as o
        where o.family_id = v_family_id and o.delivered_at is null and o.next_attempt_at <= v_now
          and (o.lease_until is null or o.lease_until <= v_now)
        order by o.occurred_at, o.event_id limit p_limit for update skip locked
    ), claimed as (
        update family_private.sheet_outbox as o set lease_id = v_lease_id, lease_until = v_lease_until,
            attempt_count = o.attempt_count + 1
        from pending as p where o.event_id = p.event_id returning o.*
    )
    select coalesce(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(c) order by c.occurred_at, c.event_id), '[]'::jsonb)
    into v_claimed from claimed as c;
    if pg_catalog.jsonb_array_length(v_claimed) = 0 then
        update family_private.sheet_family_leases as l set lease_id = null, lease_until = null
        where l.family_id = v_family_id and l.lease_id = v_lease_id;
    end if;
    return v_claimed;
end;
$$;

create function public.sheet_outbox_ack(p_event_id uuid, p_lease_id uuid, p_error text default null)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_family_id uuid;
    v_family_lease uuid;
    v_family_until timestamptz;
    v_event family_private.sheet_outbox%rowtype;
    v_now timestamptz := pg_catalog.clock_timestamp();
begin
    select o.family_id into v_family_id from family_private.sheet_outbox as o where o.event_id = p_event_id;
    if not found then raise exception using errcode = '42501', message = '佇列項目或租約無效。'; end if;
    -- Same lock order as claim: family lease, then event row.
    select l.lease_id, l.lease_until into v_family_lease, v_family_until
    from family_private.sheet_family_leases as l where l.family_id = v_family_id for update;
    select o.* into v_event from family_private.sheet_outbox as o where o.event_id = p_event_id for update;
    if p_lease_id is null or v_event.lease_id is distinct from p_lease_id then
        raise exception using errcode = '42501', message = '佇列項目或租約無效。';
    end if;
    if v_event.delivered_at is not null then return; end if; -- Repeated successful ack is harmless.
    if v_family_lease is distinct from p_lease_id or v_family_until is null or v_family_until <= v_now
       or v_event.lease_until is null or v_event.lease_until <= v_now then
        raise exception using errcode = '42501', message = '同步租約已過期，請重新取得。';
    end if;
    if p_error is null then
        update family_private.sheet_outbox as o set delivered_at = v_now, lease_until = null, last_error = null
        where o.event_id = p_event_id;
    else
        update family_private.sheet_outbox as o set lease_id = null, lease_until = null,
            last_error = pg_catalog.left(p_error, 1000),
            next_attempt_at = greatest(v_now + interval '60 seconds', v_event.lease_until + interval '15 seconds')
        where o.event_id = p_event_id;
    end if;
    if not exists (select 1 from family_private.sheet_outbox as o where o.family_id = v_family_id
                   and o.lease_id = p_lease_id and o.delivered_at is null and o.lease_until is not null) then
        update family_private.sheet_family_leases as l set lease_id = null, lease_until = null
        where l.family_id = v_family_id and l.lease_id = p_lease_id;
    end if;
end;
$$;

revoke all on function family_private.tag_location_family() from public, anon, authenticated, service_role;
revoke all on function family_private.enqueue_sheet_event(uuid, uuid, text, text, text, jsonb, jsonb, text) from public, anon, authenticated, service_role;
revoke all on function family_private.audit_business_change() from public, anon, authenticated, service_role;
revoke all on function public.calendar_list(timestamptz, timestamptz) from public, anon, authenticated, service_role;
revoke all on function public.calendar_save(uuid, text, text, text, timestamptz, timestamptz, text, integer, integer, uuid) from public, anon, authenticated, service_role;
revoke all on function public.calendar_delete(uuid, integer) from public, anon, authenticated, service_role;
revoke all on function public.app_settings_save(integer, boolean, text, boolean, uuid) from public, anon, authenticated, service_role;
revoke all on function public.sheet_sync_status() from public, anon, authenticated, service_role;
revoke all on function public.sheet_outbox_claim(integer) from public, anon, authenticated, service_role;
revoke all on function public.sheet_outbox_ack(uuid, uuid, text) from public, anon, authenticated, service_role;
grant execute on function public.calendar_list(timestamptz, timestamptz) to authenticated;
grant execute on function public.calendar_save(uuid, text, text, text, timestamptz, timestamptz, text, integer, integer, uuid) to authenticated;
grant execute on function public.calendar_delete(uuid, integer) to authenticated;
grant execute on function public.app_settings_save(integer, boolean, text, boolean, uuid) to authenticated;
grant execute on function public.sheet_sync_status() to authenticated;
grant execute on function public.sheet_outbox_claim(integer) to service_role;
grant execute on function public.sheet_outbox_ack(uuid, uuid, text) to service_role;

comment on table family_private.sheet_outbox is 'Durable business history; not removed when location sharing pauses or a family/account is deleted. Never store Auth tokens or invitation codes.';
comment on function public.sheet_outbox_claim(integer) is 'Service-only. One family per batch, shared 5-minute lease. Workers must deduplicate Google rows by stable event_id and stop before lease expiry.';
notify pgrst, 'reload schema';
commit;
