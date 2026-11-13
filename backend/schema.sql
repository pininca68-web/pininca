-- 家人在這裡 / Supabase PostgreSQL bootstrap
-- 在新 Supabase 專案的 SQL Editor，以 postgres 身分執行一次。
-- 不刪除既有資料；若 schema / RPC 已存在，整個交易會失敗並回復。
-- 不需要 pgcrypto。手機只可使用 publishable / anon 公開金鑰。
-- https://supabase.com/docs/guides/database/functions
-- https://supabase.com/docs/guides/database/postgres/row-level-security
begin;

create schema family_private;
revoke all on schema family_private from public, anon, authenticated;

create table family_private.families (
    id uuid primary key default pg_catalog.gen_random_uuid(),
    name text not null check (pg_catalog.char_length(pg_catalog.btrim(name)) between 1 and 50),
    created_at timestamptz not null default pg_catalog.clock_timestamp()
);
create table family_private.members (
    user_id uuid primary key references auth.users(id) on delete cascade,
    family_id uuid not null references family_private.families(id) on delete cascade,
    display_name text not null check (pg_catalog.char_length(pg_catalog.btrim(display_name)) between 1 and 30),
    sharing boolean not null default false,
    joined_at timestamptz not null default pg_catalog.clock_timestamp()
);
create index members_family_idx on family_private.members(family_id, joined_at, user_id);
create table family_private.locations (
    user_id uuid primary key references family_private.members(user_id) on delete cascade,
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    accuracy_m real not null check (accuracy_m between 0 and 100000),
    battery_level integer check (battery_level is null or battery_level between 0 and 100),
    recorded_at timestamptz not null check (pg_catalog.isfinite(recorded_at)),
    updated_at timestamptz not null default pg_catalog.clock_timestamp()
);
create table family_private.invites (
    token_hash bytea primary key check (pg_catalog.octet_length(token_hash) = 32),
    family_id uuid not null references family_private.families(id) on delete cascade,
    created_by uuid not null references family_private.members(user_id) on delete cascade,
    created_at timestamptz not null default pg_catalog.clock_timestamp(),
    expires_at timestamptz not null,
    unique(created_by),
    check (expires_at > created_at)
);
create index invites_family_idx on family_private.invites(family_id);

alter table family_private.families enable row level security;
alter table family_private.members enable row level security;
alter table family_private.locations enable row level security;
alter table family_private.invites enable row level security;
-- No client policies or direct table grants: clients use only checked RPCs.
revoke all on all tables in schema family_private from public, anon, authenticated;

-- First lock the caller's Auth row, even before a membership exists. All RPCs
-- therefore serialize one account's mutations without an extra lock extension.
-- Group mutations then lock family before member/invite. Pause and upload also
-- lock the same membership, preventing an in-flight upload from undoing pause.
create function family_private.require_user()
returns uuid language plpgsql security invoker set search_path = '' as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception using errcode = '28000', message = '請先登入。';
    end if;
    perform 1 from auth.users as u where u.id = v_uid for update;
    if not found then
        raise exception using errcode = '28000', message = '登入已失效，請重新登入。';
    end if;
    return v_uid;
end;
$$;

create function family_private.default_name(p_uid uuid)
returns text language sql stable security invoker set search_path = '' as $$
    select pg_catalog.left(coalesce(
        nullif(pg_catalog.btrim(u.raw_user_meta_data ->> 'display_name'), ''),
        nullif(pg_catalog.split_part(u.email, '@', 1), ''), '家人'
    ), 30) from auth.users as u where u.id = p_uid;
$$;

create function public.family_status()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_result jsonb;
begin
    select m.family_id into v_family_id from family_private.members as m
    where m.user_id = v_uid for share;
    if not found then
        return pg_catalog.jsonb_build_object('family', null, 'members', '[]'::jsonb);
    end if;
    select pg_catalog.jsonb_build_object(
        'family', pg_catalog.jsonb_build_object('id', f.id, 'name', f.name),
        'members', coalesce((
            select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                'user_id', m.user_id, 'display_name', m.display_name, 'sharing', m.sharing,
                'latitude', case when m.sharing then l.latitude end,
                'longitude', case when m.sharing then l.longitude end,
                'accuracy_m', case when m.sharing then l.accuracy_m end,
                'battery_level', case when m.sharing then l.battery_level end,
                'recorded_at', case when m.sharing then l.recorded_at end,
                'updated_at', case when m.sharing then l.updated_at end
            ) order by m.joined_at, m.user_id)
            from family_private.members as m
            left join family_private.locations as l on l.user_id = m.user_id
            where m.family_id = v_family_id
        ), '[]'::jsonb)
    ) into v_result from family_private.families as f where f.id = v_family_id;
    return coalesce(v_result, pg_catalog.jsonb_build_object('family', null, 'members', '[]'::jsonb));
end;
$$;

create function public.create_family(p_name text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_name text := pg_catalog.btrim(p_name);
    v_family_id uuid;
begin
    if v_name is null or pg_catalog.char_length(v_name) not between 1 and 50 then
        raise exception using errcode = '22023', message = '家庭名稱請輸入 1 到 50 個字。';
    end if;
    if exists (select 1 from family_private.members as m where m.user_id = v_uid) then
        raise exception using errcode = '23505', message = '您已加入家庭，請先離開目前的家庭。';
    end if;
    insert into family_private.families(name) values (v_name) returning id into v_family_id;
    insert into family_private.members(user_id, family_id, display_name)
    values (v_uid, v_family_id, family_private.default_name(v_uid));
    return pg_catalog.jsonb_build_object('id', v_family_id, 'name', v_name);
end;
$$;

create function public.create_invite()
returns text language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_token text;
    v_now timestamptz := pg_catalog.clock_timestamp();
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid;
    if not found then
        raise exception using errcode = '42501', message = '請先建立或加入家庭。';
    end if;
    perform 1 from family_private.families as f where f.id = v_family_id for update;
    perform 1 from family_private.members as m
    where m.user_id = v_uid and m.family_id = v_family_id for update;
    if not found then
        raise exception using errcode = '42501', message = '您已離開此家庭。';
    end if;
    if (select pg_catalog.count(*) from family_private.members as m where m.family_id = v_family_id) >= 12 then
        raise exception using errcode = '22023', message = '家庭成員已達 12 人上限。';
    end if;
    -- One active invitation per creator; new invitations replace their old one.
    -- Store only SHA-256. A random UUID has no short numeric code to guess.
    delete from family_private.invites as i
    where i.family_id = v_family_id and (i.created_by = v_uid or i.expires_at <= v_now);
    v_token := pg_catalog.gen_random_uuid()::text;
    insert into family_private.invites(token_hash, family_id, created_by, created_at, expires_at)
    values (pg_catalog.sha256(pg_catalog.convert_to(v_token, 'UTF8')), v_family_id, v_uid,
            v_now, v_now + interval '24 hours');
    return v_token;
end;
$$;

create function public.join_family(p_invite_code text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_token text := pg_catalog.lower(pg_catalog.btrim(p_invite_code));
    v_hash bytea;
    v_family_id uuid;
    v_family_name text;
    v_expires_at timestamptz;
begin
    if exists (select 1 from family_private.members as m where m.user_id = v_uid) then
        raise exception using errcode = '23505', message = '您已加入家庭，請先離開目前的家庭。';
    end if;
    if v_token is null or v_token !~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
        raise exception using errcode = '22023', message = '邀請碼無效、已使用或已過期。';
    end if;
    v_hash := pg_catalog.sha256(pg_catalog.convert_to(v_token, 'UTF8'));
    -- Find family first; acquire locks in family->invite order, then recheck.
    select i.family_id into v_family_id from family_private.invites as i where i.token_hash = v_hash;
    if not found then
        raise exception using errcode = '22023', message = '邀請碼無效、已使用或已過期。';
    end if;
    select f.name into v_family_name from family_private.families as f
    where f.id = v_family_id for update;
    if not found then
        raise exception using errcode = '22023', message = '邀請碼無效、已使用或已過期。';
    end if;
    select i.expires_at into v_expires_at from family_private.invites as i
    where i.token_hash = v_hash and i.family_id = v_family_id for update;
    if not found or v_expires_at <= pg_catalog.clock_timestamp() then
        raise exception using errcode = '22023', message = '邀請碼無效、已使用或已過期。';
    end if;
    if (select pg_catalog.count(*) from family_private.members as m where m.family_id = v_family_id) >= 12 then
        raise exception using errcode = '22023', message = '家庭成員已達 12 人上限。';
    end if;
    insert into family_private.members(user_id, family_id, display_name)
    values (v_uid, v_family_id, family_private.default_name(v_uid));
    delete from family_private.invites as i where i.token_hash = v_hash;
    return pg_catalog.jsonb_build_object('id', v_family_id, 'name', v_family_name);
end;
$$;

create function public.set_display_name(p_name text)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_name text := pg_catalog.btrim(p_name);
begin
    if v_name is null or pg_catalog.char_length(v_name) not between 1 and 30 then
        raise exception using errcode = '22023', message = '顯示名稱請輸入 1 到 30 個字。';
    end if;
    perform 1 from family_private.members as m where m.user_id = v_uid for update;
    if not found then
        raise exception using errcode = '42501', message = '請先建立或加入家庭。';
    end if;
    update family_private.members as m set display_name = v_name where m.user_id = v_uid;
end;
$$;

create function public.set_sharing(p_enabled boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
begin
    if p_enabled is null then
        raise exception using errcode = '22023', message = '請指定是否分享位置。';
    end if;
    perform 1 from family_private.members as m where m.user_id = v_uid for update;
    if not found then
        if not p_enabled then return; end if;
        raise exception using errcode = '42501', message = '請先建立或加入家庭。';
    end if;
    update family_private.members as m set sharing = p_enabled where m.user_id = v_uid;
    if not p_enabled then
        delete from family_private.locations as l where l.user_id = v_uid;
    end if;
end;
$$;

create function public.publish_location(
    p_lat double precision, p_lng double precision, p_accuracy real,
    p_battery integer, p_recorded_at timestamptz
)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_sharing boolean;
    v_now timestamptz := pg_catalog.clock_timestamp();
begin
    select m.sharing into v_sharing from family_private.members as m
    where m.user_id = v_uid for update;
    if not found then
        raise exception using errcode = '42501', message = '請先建立或加入家庭。';
    end if;
    if not v_sharing then
        raise exception using errcode = '42501', message = '位置分享已暫停，未上傳位置。';
    end if;
    -- Bounded comparisons reject PostgreSQL NaN and +/-Infinity as well.
    if p_lat is null or not (p_lat between -90 and 90)
       or p_lng is null or not (p_lng between -180 and 180)
       or p_accuracy is null or not (p_accuracy between 0 and 100000)
       or (p_battery is not null and not (p_battery between 0 and 100))
       or p_recorded_at is null or not pg_catalog.isfinite(p_recorded_at)
       or p_recorded_at < v_now - interval '5 minutes'
       or p_recorded_at > v_now + interval '1 minute' then
        raise exception using errcode = '22023', message = '定位資料無效或已過期，請重新取得位置。';
    end if;
    insert into family_private.locations as current_location
        (user_id, latitude, longitude, accuracy_m, battery_level, recorded_at, updated_at)
    values (v_uid, p_lat, p_lng, p_accuracy, p_battery, p_recorded_at, v_now)
    on conflict (user_id) do update set
        latitude = excluded.latitude, longitude = excluded.longitude,
        accuracy_m = excluded.accuracy_m, battery_level = excluded.battery_level,
        recorded_at = excluded.recorded_at, updated_at = excluded.updated_at
    where excluded.recorded_at > current_location.recorded_at;
end;
$$;

create function public.leave_family()
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid;
    if not found then return; end if;
    perform 1 from family_private.families as f where f.id = v_family_id for update;
    perform 1 from family_private.members as m where m.user_id = v_uid for update;
    delete from family_private.members as m where m.user_id = v_uid;
    -- FK cascades remove this member's position and pending invitations.
    if not exists (select 1 from family_private.members as m where m.family_id = v_family_id) then
        delete from family_private.families as f where f.id = v_family_id;
    end if;
end;
$$;

revoke all on all functions in schema family_private from public, anon, authenticated;
revoke all on function public.family_status() from public, anon, authenticated;
revoke all on function public.create_family(text) from public, anon, authenticated;
revoke all on function public.join_family(text) from public, anon, authenticated;
revoke all on function public.create_invite() from public, anon, authenticated;
revoke all on function public.set_display_name(text) from public, anon, authenticated;
revoke all on function public.set_sharing(boolean) from public, anon, authenticated;
revoke all on function public.publish_location(double precision, double precision, real, integer, timestamptz) from public, anon, authenticated;
revoke all on function public.leave_family() from public, anon, authenticated;
grant execute on function public.family_status() to authenticated;
grant execute on function public.create_family(text) to authenticated;
grant execute on function public.join_family(text) to authenticated;
grant execute on function public.create_invite() to authenticated;
grant execute on function public.set_display_name(text) to authenticated;
grant execute on function public.set_sharing(boolean) to authenticated;
grant execute on function public.publish_location(double precision, double precision, real, integer, timestamptz) to authenticated;
grant execute on function public.leave_family() to authenticated;

comment on function public.create_invite() is 'Single-use random UUID, expires after 24h; replaces the caller''s previous invitation.';
comment on function public.family_status() is 'Current family only. Positions are latest-known; clients must show recorded_at and mark stale data.';
comment on function public.set_sharing(boolean) is 'Pause deletes the latest position; shared actor/member locks prevent in-flight uploads restoring it.';
commit;
