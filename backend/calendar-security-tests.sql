-- Development database only. Run as postgres AFTER schema.sql and migration 002.
-- No pgTAP/downloads required. All fixtures, claims and acknowledgements roll back.
-- On any failure issue ROLLBACK. This does not test simultaneous connections,
-- HTTP JWT verification, Android notifications, or the external Google API.
begin;
create temporary table cal_context (
    actor text primary key, user_id uuid not null default pg_catalog.gen_random_uuid(),
    family_id uuid, event_id uuid, token text, claims jsonb, next_claims jsonb
);
insert into pg_temp.cal_context(actor) values ('A'), ('B'), ('C');
insert into auth.users(id, email, raw_user_meta_data)
select user_id, 'cal_test_' || pg_catalog.replace(user_id::text, '-', '') || '@example.invalid',
       pg_catalog.jsonb_build_object('display_name', actor) from pg_temp.cal_context;
grant select, update on pg_temp.cal_context to authenticated, service_role;
create function pg_temp.cal_assert(p_ok boolean, p_message text)
returns void language plpgsql security invoker as $$
begin if p_ok is distinct from true then raise exception 'FAIL: %', p_message; end if; end;
$$;
create function pg_temp.cal_expect_error(p_sql text, p_state text)
returns void language plpgsql security invoker as $$
declare v_state text; v_message text;
begin
    begin execute p_sql;
    exception when others then
        get stacked diagnostics v_state = returned_sqlstate, v_message = message_text;
        if v_state <> p_state then raise exception 'Expected %, got %: %', p_state, v_state, v_message; end if;
        return;
    end;
    raise exception 'Expected %, but statement succeeded: %', p_state, p_sql;
end;
$$;
create function pg_temp.cal_actor(p_actor text)
returns void language plpgsql security invoker as $$
declare v_uid uuid;
begin
    select user_id into strict v_uid from pg_temp.cal_context where actor = p_actor;
    perform pg_catalog.set_config('request.jwt.claim.sub', v_uid::text, true);
    perform pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', v_uid, 'role', 'authenticated')::text, true);
end;
$$;
grant execute on function pg_temp.cal_assert(boolean,text), pg_temp.cal_expect_error(text,text) to authenticated, anon, service_role;
grant execute on function pg_temp.cal_actor(text) to authenticated;
do $$
declare v_schema text;
begin
    select nspname into v_schema from pg_catalog.pg_namespace where oid = pg_catalog.pg_my_temp_schema();
    execute pg_catalog.format('grant usage on schema %I to authenticated, anon, service_role', v_schema);
end;
$$;

select pg_temp.cal_assert(
    (select pg_catalog.count(*) = 4 and pg_catalog.bool_and(c.relrowsecurity)
     from pg_catalog.pg_class as c join pg_catalog.pg_namespace as n on n.oid = c.relnamespace
     where n.nspname = 'family_private' and c.relname in ('calendar_events','app_settings','sheet_outbox','sheet_family_leases')),
    'All new tables must have RLS');
select pg_temp.cal_assert(not pg_catalog.has_function_privilege('authenticated','public.sheet_outbox_claim(integer)','EXECUTE'), 'Clients must not claim the server outbox');
select pg_temp.cal_assert(not pg_catalog.has_function_privilege('anon','public.calendar_list(timestamptz,timestamptz)','EXECUTE'), 'Anonymous must not list family events');

set local role anon;
select pg_temp.cal_expect_error('select public.calendar_list(now(),now()+interval ''1 day'')', '42501');
select pg_temp.cal_expect_error('select public.sheet_sync_status()', '42501');
reset role;
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', '', true);
select pg_catalog.set_config('request.jwt.claims', '{}', true);
select pg_temp.cal_expect_error('select public.calendar_list(now(),now()+interval ''1 day'')', '28000');
select pg_temp.cal_expect_error('select public.sheet_sync_status()', '28000');
select pg_temp.cal_expect_error('select * from family_private.calendar_events', '42501');
select pg_temp.cal_expect_error('select * from family_private.sheet_outbox', '42501');
select pg_temp.cal_expect_error('select public.sheet_outbox_claim(1)', '42501');
select pg_temp.cal_expect_error('select public.sheet_outbox_ack(gen_random_uuid(),gen_random_uuid(),null)', '42501');

-- A creates an event and settings, without giving any family ID to list RPCs.
select pg_temp.cal_actor('A');
select pg_temp.cal_assert(public.sheet_sync_status() = '{"queued":0,"failed":0,"last_delivered_at":null}'::jsonb, 'Unjoined account must see no sync metadata');
update pg_temp.cal_context set family_id = (public.create_family('行事曆測試家庭') ->> 'id')::uuid where actor = 'A';
update pg_temp.cal_context set token = public.create_invite() where actor = 'A';
update pg_temp.cal_context set event_id = (public.calendar_save(null, '全家出遊', '帶水壺', '集合地點',
    now()+interval '1 hour', now()+interval '2 hours', 'Asia/Taipei', 15, null,
    (select family_id from pg_temp.cal_context where actor='A')) ->> 'id')::uuid where actor = 'A';
select public.app_settings_save(60, true, 'TOP', true, (select family_id from pg_temp.cal_context where actor='A'));
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now(),now()+interval '1 day')->'events')=1, 'Creator must see the event');
select pg_temp.cal_assert(public.calendar_list(now(),now()+interval '1 day') #>> '{events,0,timezone}' = 'Asia/Taipei', 'IANA timezone must round-trip');
select pg_temp.cal_assert(public.calendar_list(now(),now()+interval '1 day') #>> '{events,0,version}' = '1', 'New event must start at version one');
select pg_temp.cal_assert(public.calendar_list(now(),now()+interval '1 day')->>'server_time' is not null, 'Response must include server time');
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now()+interval '2 hours',now()+interval '3 hours')->'events')=0, 'End boundary must not overlap the next range');
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now()+interval '90 minutes',now()+interval '3 hours')->'events')=1, 'An event overlapping the range must be returned');
select pg_temp.cal_expect_error('select public.calendar_list(now(),now()+interval ''401 days'')', '22023');
select pg_temp.cal_expect_error('select public.calendar_list(now(),now())', '22023');
select pg_temp.cal_expect_error('select public.calendar_list(''-infinity''::timestamptz,now())', '22023');
select pg_temp.cal_expect_error('select public.calendar_save(null,'''','''','''',now(),now()+interval ''1 hour'',''Asia/Taipei'',15,null)', '22023');
select pg_temp.cal_expect_error('select public.calendar_save(null,''x'','''','''',now(),now(),''Asia/Taipei'',15,null)', '22023');
select pg_temp.cal_expect_error('select public.calendar_save(null,''x'','''','''',now(),''infinity''::timestamptz,''Asia/Taipei'',15,null)', '22023');
select pg_temp.cal_expect_error('select public.calendar_save(null,''x'','''','''',now(),now()+interval ''1 hour'',''Invalid/Timezone'',15,null)', '22023');
select pg_temp.cal_expect_error('select public.calendar_save(null,''x'','''','''',now(),now()+interval ''1 hour'',''Asia/Taipei'',7,null)', '22023');
select pg_temp.cal_expect_error('select public.calendar_save(null,''x'',repeat(''x'',4001),'''',now(),now()+interval ''1 hour'',''Asia/Taipei'',15,null)', '22023');
select pg_temp.cal_expect_error('select public.app_settings_save(17,true,''TOP'',true)', '22023');

-- B joins and can edit/delete an event created by A. Stale versions fail.
select pg_temp.cal_actor('B');
select public.join_family((select token from pg_temp.cal_context where actor='A'));
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now(),now()+interval '1 day')->'events')=1, 'Accepted family member must share the calendar');
select pg_temp.cal_assert((public.calendar_save((select event_id from pg_temp.cal_context where actor='A'),
    '家人修改的行程', '修改內容', '新地點', now()+interval '1 hour', now()+interval '2 hours', 'Asia/Taipei', 30, 1,
    (select family_id from pg_temp.cal_context where actor='A')) ->> 'version')::integer=2, 'Another family member must be able to edit and increment version');
select pg_temp.cal_actor('A');
select pg_temp.cal_expect_error('select public.calendar_save((select event_id from pg_temp.cal_context where actor=''A''),''舊版本'','''','''',now(),now()+interval ''1 hour'',''Asia/Taipei'',15,1)', '40001');
select pg_temp.cal_expect_error('select public.calendar_delete((select event_id from pg_temp.cal_context where actor=''A''),1)', '40001');
select pg_temp.cal_actor('B');
select public.calendar_delete((select event_id from pg_temp.cal_context where actor='A'),2);
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now(),now()+interval '1 day')->'events')=0, 'Another family member must be able to delete');
select pg_temp.cal_actor('A');
update pg_temp.cal_context set event_id = (public.calendar_save(null, '作者離開後仍保留', '', '',
    now()+interval '1 hour',now()+interval '2 hours','Asia/Taipei',0,null) ->> 'id')::uuid where actor='A';

-- C cannot use a guessed event ID or a stale bound-family ID to cross families.
select pg_temp.cal_actor('C');
update pg_temp.cal_context set family_id=(public.create_family('另一個獨立家庭')->>'id')::uuid where actor='C';
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now(),now()+interval '1 day')->'events')=0, 'Unrelated family must not see A''s event');
select pg_temp.cal_expect_error('select public.calendar_save((select event_id from pg_temp.cal_context where actor=''A''),''越權'','''','''',now(),now()+interval ''1 hour'',''Asia/Taipei'',15,1)', '42501');
select pg_temp.cal_expect_error('select public.calendar_delete((select event_id from pg_temp.cal_context where actor=''A''),1)', '42501');
select pg_temp.cal_expect_error('select public.calendar_save(null,''舊家庭畫面'','''','''',now(),now()+interval ''1 hour'',''Asia/Taipei'',15,null,(select family_id from pg_temp.cal_context where actor=''A''))', '42501');
select pg_temp.cal_expect_error('select public.app_settings_save(60,true,''TOP'',true,(select family_id from pg_temp.cal_context where actor=''A''))', '42501');
select pg_temp.cal_assert((public.sheet_sync_status()->>'queued')::integer=2, 'Sync status must count only C''s family/member creation');
select pg_temp.cal_assert((public.sheet_sync_status()->>'failed')::integer=0 and public.sheet_sync_status()->'last_delivered_at'='null'::jsonb, 'No unrelated delivery/error metadata may leak');
reset role;

-- More than 500 matching events must be an explicit error, never a partial list.
insert into family_private.calendar_events(family_id,title,starts_at,ends_at,created_by)
select (select family_id from pg_temp.cal_context where actor='A'), 'limit fixture',
       now()+n*interval '1 minute',now()+n*interval '1 minute'+interval '30 seconds',
       (select user_id from pg_temp.cal_context where actor='A') from pg_catalog.generate_series(1,501) as g(n);
set local role authenticated;
select pg_temp.cal_actor('A');
select pg_temp.cal_expect_error('select public.calendar_list(now(),now()+interval ''1 day'')','22023');
reset role;
delete from family_private.calendar_events where family_id=(select family_id from pg_temp.cal_context where actor='A') and title='limit fixture';

-- Position pause clears live data but its disclosed business history survives.
set local role authenticated;
select pg_temp.cal_actor('A');
select public.set_sharing(true);
select public.publish_location(25.03,121.56,12,80,pg_catalog.clock_timestamp());
select public.set_sharing(false);
reset role;
select pg_temp.cal_assert(not exists(select 1 from family_private.locations where user_id=(select user_id from pg_temp.cal_context where actor='A')), 'Paused live position must still be deleted');
select pg_temp.cal_assert(exists(select 1 from family_private.sheet_outbox where entity_type='location' and operation='DELETE'
    and family_id=(select family_id from pg_temp.cal_context where actor='A') and (payload#>>'{data,latitude}')::double precision=25.03
    and payload->>'family_name'='行事曆測試家庭' and payload->>'display_name'='A'), 'Deletion audit must retain complete position and names');
select pg_temp.cal_assert(not exists(select 1 from family_private.sheet_outbox
    where family_id=(select family_id from pg_temp.cal_context where actor='A')
      and pg_catalog.strpos(payload::text,(select token from pg_temp.cal_context where actor='A'))>0), 'Audit must never contain invitation bearer codes');
select pg_temp.cal_assert(exists(select 1 from family_private.sheet_outbox where entity_type='settings'
    and family_id=(select family_id from pg_temp.cal_context where actor='A')
    and payload#>>'{data,interval_minutes}'='60' and payload#>>'{data,calendar_reminders_enabled}'='true'), 'Settings changes must be audited');

-- Put this fixture family first. All delivery actions remain transactional and
-- are rolled back; no Google request is made by this test.
update family_private.sheet_outbox set occurred_at=pg_catalog.clock_timestamp()-interval '100 years'
where family_id=(select family_id from pg_temp.cal_context where actor='A');
set local role service_role;
select pg_temp.cal_expect_error('select public.sheet_outbox_claim(51)','22023');
update pg_temp.cal_context set claims=public.sheet_outbox_claim(2) where actor='A';
select pg_temp.cal_assert(pg_catalog.jsonb_array_length((select claims from pg_temp.cal_context where actor='A'))=2,'Service must claim a bounded batch');
select pg_temp.cal_assert(
    (select pg_catalog.bool_and((e.value->>'family_id')::uuid=(select family_id from pg_temp.cal_context where actor='A'))
     from pg_catalog.jsonb_array_elements((select claims from pg_temp.cal_context where actor='A')) as e(value)), 'A claimed batch must contain only one family');
update pg_temp.cal_context set next_claims=public.sheet_outbox_claim(2) where actor='A';
select pg_temp.cal_assert(not exists(select 1 from pg_catalog.jsonb_array_elements((select next_claims from pg_temp.cal_context where actor='A')) as e(value)
    where (e.value->>'family_id')::uuid=(select family_id from pg_temp.cal_context where actor='A')), 'A second worker must skip the leased family');
select pg_temp.cal_expect_error('select public.sheet_outbox_ack((select(claims#>>''{0,event_id}'')::uuid from pg_temp.cal_context where actor=''A''),gen_random_uuid(),null)','42501');
select public.sheet_outbox_ack((select(claims#>>'{0,event_id}')::uuid from pg_temp.cal_context where actor='A'),
    (select(claims#>>'{0,lease_id}')::uuid from pg_temp.cal_context where actor='A'),null);
select public.sheet_outbox_ack((select(claims#>>'{0,event_id}')::uuid from pg_temp.cal_context where actor='A'),
    (select(claims#>>'{0,lease_id}')::uuid from pg_temp.cal_context where actor='A'),null); -- Idempotent ack.
select public.sheet_outbox_ack((select(claims#>>'{1,event_id}')::uuid from pg_temp.cal_context where actor='A'),
    (select(claims#>>'{1,lease_id}')::uuid from pg_temp.cal_context where actor='A'),'test retry');
reset role;
select pg_temp.cal_assert(exists(select 1 from family_private.sheet_outbox where event_id=(select(claims#>>'{0,event_id}')::uuid from pg_temp.cal_context where actor='A')
    and delivered_at is not null), 'Successful ack must mark delivery');
select pg_temp.cal_assert(exists(select 1 from family_private.sheet_outbox where event_id=(select(claims#>>'{1,event_id}')::uuid from pg_temp.cal_context where actor='A')
    and delivered_at is null and lease_id is null and last_error='test retry'
    and next_attempt_at >= (select(claims#>>'{1,lease_until}')::timestamptz+interval '15 seconds' from pg_temp.cal_context where actor='A')),
    'Error ack must clear the row lease and defer retry beyond the previous lease');
select pg_temp.cal_assert(exists(select 1 from family_private.sheet_family_leases where family_id=(select family_id from pg_temp.cal_context where actor='A') and lease_id is null), 'All batch acknowledgements must release the family lease');

-- The retry keeps its original event ID and obtains a fresh lease.
update family_private.sheet_outbox set next_attempt_at=pg_catalog.clock_timestamp()
where event_id=(select(claims#>>'{1,event_id}')::uuid from pg_temp.cal_context where actor='A');
set local role service_role;
update pg_temp.cal_context set next_claims=public.sheet_outbox_claim(1) where actor='A';
select pg_temp.cal_assert((select next_claims#>>'{0,event_id}'=claims#>>'{1,event_id}' from pg_temp.cal_context where actor='A'), 'Retries must not invent a new event ID');
select pg_temp.cal_assert((select next_claims#>>'{0,lease_id}'<>claims#>>'{1,lease_id}' from pg_temp.cal_context where actor='A'), 'Retry must receive a new lease');
reset role;
update family_private.sheet_family_leases set lease_until=pg_catalog.clock_timestamp()-interval '1 second'
where family_id=(select family_id from pg_temp.cal_context where actor='A');
update family_private.sheet_outbox set lease_until=pg_catalog.clock_timestamp()-interval '1 second'
where event_id=(select(next_claims#>>'{0,event_id}')::uuid from pg_temp.cal_context where actor='A');
set local role service_role;
select pg_temp.cal_expect_error('select public.sheet_outbox_ack((select(next_claims#>>''{0,event_id}'')::uuid from pg_temp.cal_context where actor=''A''),(select(next_claims#>>''{0,lease_id}'')::uuid from pg_temp.cal_context where actor=''A''),null)','42501');
reset role;

set local role authenticated;
select pg_temp.cal_actor('A');
select pg_temp.cal_assert((public.sheet_sync_status()->>'failed')::integer=1 and public.sheet_sync_status()->>'last_delivered_at' is not null, 'Family status must expose only aggregate pending/failure/delivery state');
select public.leave_family();
select pg_temp.cal_assert(public.sheet_sync_status() = '{"queued":0,"failed":0,"last_delivered_at":null}'::jsonb, 'Departed user must lose historical sync metadata access');
select pg_temp.cal_actor('B');
select pg_temp.cal_assert(pg_catalog.jsonb_array_length(public.calendar_list(now(),now()+interval '1 day')->'events')=1, 'Leaving creator must not delete the family event');
select public.leave_family();
reset role;
select pg_temp.cal_assert(not exists(select 1 from family_private.families where id=(select family_id from pg_temp.cal_context where actor='A')), 'Last member may remove the empty family');
select pg_temp.cal_assert(not exists(select 1 from family_private.app_settings where user_id=(select user_id from pg_temp.cal_context where actor='A')), 'Member settings must cascade on departure');
select pg_temp.cal_assert(exists(select 1 from family_private.sheet_outbox where family_id=(select family_id from pg_temp.cal_context where actor='A')
    and entity_type='calendar' and operation='DELETE' and payload->>'family_name'='行事曆測試家庭'), 'Outbox and labels must survive final family/calendar cascade');
set local role authenticated;
select pg_temp.cal_actor('C');
select pg_temp.cal_assert((public.sheet_sync_status()->>'failed')::integer=0 and public.sheet_sync_status()->'last_delivered_at'='null'::jsonb, 'Another family must not inherit A''s failure/delivery state');
reset role;
select 'PASS: calendar authorization, shared edits, version conflicts, validation, audit history, family leases and sync-status isolation' as result;
rollback;
