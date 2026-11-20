-- Apply once, as postgres, AFTER schema.sql and migration 002.
-- Stop the old Sheets worker/scheduler and wait for in-flight runs to finish
-- BEFORE applying. See ../MANUAL_LOCATION_UPGRADE.md for external cleanup.
begin;

-- Drain existing writers before removing old automated positions and consent.
-- Old RPCs serialize on the caller's Auth/member locks. A writer which already
-- checked sharing completes before this lock, so its position is also removed.
lock table family_private.members, family_private.locations in access exclusive mode;
drop trigger audit_location_write on family_private.locations;
drop trigger audit_location_delete on family_private.locations;
delete from family_private.locations;
update family_private.members set sharing = false where sharing;

-- These rows include both pending payloads and already-delivered local copies.
-- This does NOT delete rows previously written to an external Google Sheet.
delete from family_private.sheet_outbox where entity_type = 'location';
alter table family_private.sheet_outbox drop constraint sheet_outbox_entity_type_check;
alter table family_private.sheet_outbox add constraint sheet_outbox_entity_type_check
    check (entity_type in ('family', 'member', 'calendar', 'settings'));

-- Retire the old endpoint even for callers accidentally granted privileges later.
create or replace function public.publish_location(
    p_lat double precision, p_lng double precision, p_accuracy real,
    p_battery integer, p_recorded_at timestamptz
)
returns void language plpgsql security definer set search_path = '' as $$
begin
    raise exception using errcode = '42501',
        message = '自動定位上傳已停用，請更新 App 並由本人按下手動分享。';
end;
$$;
revoke all on function public.publish_location(double precision, double precision, real, integer, timestamptz)
    from public, anon, authenticated, service_role;

create function public.location_privacy_status()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := family_private.require_user();
begin
    return pg_catalog.jsonb_build_object('privacy_version', 1, 'manual_only', true,
        'location_history_export', false);
end;
$$;

-- No default for expected_family_id: a fix acquired for a previous family must
-- never be sent to a newly joined family after a membership change.
create function public.publish_manual_location(
    p_lat double precision, p_lng double precision, p_accuracy real,
    p_battery integer, p_recorded_at timestamptz, p_expected_family_id uuid
)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_sharing boolean;
    v_now timestamptz;
    v_stored boolean;
    v_latest family_private.locations%rowtype;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid;
    if v_family_id is null or p_expected_family_id is distinct from v_family_id then
        raise exception using errcode = '42501', message = '家庭已變更，請重新確認後再手動分享位置。';
    end if;
    perform 1 from family_private.families as f where f.id = v_family_id for update;
    select m.sharing into v_sharing from family_private.members as m
    where m.user_id = v_uid and m.family_id = p_expected_family_id for update;
    if not found or not v_sharing then
        raise exception using errcode = '42501', message = '位置分享已暫停，未上傳位置。';
    end if;
    v_now := pg_catalog.clock_timestamp();
    -- Bounded comparisons also reject PostgreSQL NaN and +/-Infinity.
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
    v_stored := found;
    select l.* into v_latest from family_private.locations as l where l.user_id = v_uid;
    return pg_catalog.jsonb_build_object('stored', v_stored, 'recorded_at', v_latest.recorded_at,
        'updated_at', v_latest.updated_at, 'privacy_version', 1);
end;
$$;

create function public.delete_my_location_data()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    v_uid uuid := family_private.require_user();
    v_family_id uuid;
    v_latest_count bigint;
    v_history_count bigint;
begin
    select m.family_id into v_family_id from family_private.members as m where m.user_id = v_uid;
    if v_family_id is not null then
        perform 1 from family_private.families as f where f.id = v_family_id for update;
        perform 1 from family_private.members as m where m.user_id = v_uid for update;
        update family_private.members as m set sharing = false where m.user_id = v_uid and m.sharing;
    end if;
    delete from family_private.locations as l where l.user_id = v_uid;
    get diagnostics v_latest_count = row_count;
    -- Do not trust the actor column: old baseline snapshots have a null actor.
    -- Subject/entity IDs identify the location owner across former families too.
    delete from family_private.sheet_outbox as o where o.entity_type = 'location'
        and (o.entity_id = v_uid::text or o.payload #>> '{data,user_id}' = v_uid::text
             or o.payload #>> '{old,user_id}' = v_uid::text);
    get diagnostics v_history_count = row_count;
    return pg_catalog.jsonb_build_object('sharing', false,
        'deleted_latest_count', v_latest_count, 'deleted_location_history_count', v_history_count,
        'scope', 'server_location_only', 'external_cleanup_required', true,
        'external_retention_note', 'Previously exported Google Sheets rows, if any, require the sheet owner to delete them manually.');
end;
$$;

revoke all on function public.location_privacy_status() from public, anon, authenticated, service_role;
revoke all on function public.publish_manual_location(double precision, double precision, real, integer, timestamptz, uuid)
    from public, anon, authenticated, service_role;
revoke all on function public.delete_my_location_data() from public, anon, authenticated, service_role;
grant execute on function public.location_privacy_status() to authenticated;
grant execute on function public.publish_manual_location(double precision, double precision, real, integer, timestamptz, uuid) to authenticated;
grant execute on function public.delete_my_location_data() to authenticated;
comment on table family_private.locations is 'Only the latest voluntarily submitted location per member. No location audit/export snapshots.';
comment on table family_private.sheet_outbox is 'Non-GPS business history only. Previously exported Google Sheet location rows require external owner cleanup.';
comment on function public.publish_location(double precision, double precision, real, integer, timestamptz) is 'Retired. Always refuses legacy automated uploads.';
comment on function public.location_privacy_status() is 'Authenticated capability check. Clients must confirm this version before acquiring GPS.';
comment on function public.publish_manual_location(double precision, double precision, real, integer, timestamptz, uuid) is 'Client must obtain fresh GPS only after an explicit user gesture; latest only. A server cannot prove a physical user gesture.';
comment on function public.delete_my_location_data() is 'Stops sharing and deletes the caller latest position and local GPS snapshots. Does not delete external Sheets, backups, calendars or the account.';
notify pgrst, 'reload schema';
commit;
