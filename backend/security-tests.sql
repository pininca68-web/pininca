-- Execute AFTER schema.sql, as postgres, on a development Supabase database.
-- No pgTAP extension is needed. Test users/data/temporary helpers are rolled back.
-- If an assertion fails, issue ROLLBACK; do not commit this transaction.
-- This verifies actual PostgreSQL roles and RPC behavior; it does not simulate
-- simultaneous connections or exercise Android / Supabase HTTP authentication.
begin;

create temporary table fl_security_context (
    actor text primary key,
    user_id uuid not null default pg_catalog.gen_random_uuid(),
    token text,
    extra_token text,
    family_id uuid
);
insert into pg_temp.fl_security_context(actor) values ('A'), ('B'), ('C'), ('D');
insert into pg_temp.fl_security_context(actor)
select 'fill' || n::text from pg_catalog.generate_series(1, 10) as g(n);
insert into auth.users(id, email, raw_user_meta_data)
select user_id, 'fl_test_' || pg_catalog.replace(user_id::text, '-', '') || '@example.invalid',
       pg_catalog.jsonb_build_object('display_name', actor)
from pg_temp.fl_security_context;
grant select, update on pg_temp.fl_security_context to authenticated;

create function pg_temp.fl_assert(p_condition boolean, p_message text)
returns void language plpgsql security invoker as $$
begin
    if p_condition is distinct from true then
        raise exception 'FAIL: %', p_message;
    end if;
end;
$$;
create function pg_temp.fl_expect_error(p_sql text, p_state text)
returns void language plpgsql security invoker as $$
declare
    v_actual text;
    v_message text;
begin
    begin
        execute p_sql;
    exception when others then
        get stacked diagnostics v_actual = returned_sqlstate, v_message = message_text;
        if v_actual <> p_state then
            raise exception 'Expected SQLSTATE %, got %: %', p_state, v_actual, v_message;
        end if;
        return;
    end;
    raise exception 'Expected SQLSTATE %, but statement succeeded: %', p_state, p_sql;
end;
$$;
grant execute on function pg_temp.fl_assert(boolean, text) to authenticated, anon;
grant execute on function pg_temp.fl_expect_error(text, text) to authenticated, anon;
do $$
declare v_schema text;
begin
    select n.nspname into v_schema from pg_catalog.pg_namespace as n
    where n.oid = pg_catalog.pg_my_temp_schema();
    execute pg_catalog.format('grant usage on schema %I to authenticated, anon', v_schema);
end;
$$;

-- RLS is enabled on every private data table; no direct client table permissions.
select pg_temp.fl_assert(
    (select pg_catalog.count(*) = 4 and pg_catalog.bool_and(c.relrowsecurity)
     from pg_catalog.pg_class as c join pg_catalog.pg_namespace as n on n.oid = c.relnamespace
     where n.nspname = 'family_private' and c.relkind = 'r'), 'Every private table must have RLS');
select pg_temp.fl_assert(not pg_catalog.has_schema_privilege('authenticated', 'family_private', 'USAGE'),
                        'Authenticated must not access the private schema');
select pg_temp.fl_assert(not pg_catalog.has_function_privilege('anon', 'public.family_status()', 'EXECUTE'),
                        'Anonymous must not execute family RPCs');
select pg_temp.fl_assert(
    not pg_catalog.has_table_privilege('authenticated', 'family_private.locations', 'SELECT,INSERT,UPDATE,DELETE'),
    'Authenticated must not directly read or mutate positions');

-- No-session access must fail even if the role can execute the RPC.
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', '', true);
select pg_catalog.set_config('request.jwt.claims', '{}', true);
select pg_temp.fl_expect_error('select public.family_status()', '28000');
select pg_temp.fl_expect_error('select * from family_private.members', '42501');
select pg_temp.fl_expect_error('select family_private.require_user()', '42501');
reset role;
set local role anon;
select pg_temp.fl_expect_error('select public.family_status()', '42501');
select pg_temp.fl_expect_error('select * from family_private.locations', '42501');
reset role;

-- A creates a family. Sharing is off until explicitly enabled.
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'A'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'A'), 'role', 'authenticated')::text, true);
select pg_temp.fl_assert(public.family_status() = '{"family":null,"members":[]}'::jsonb, 'New account must see no family');
select public.set_sharing(false); -- Revoking before joining is an idempotent no-op.
select pg_temp.fl_expect_error('select public.set_sharing(true)', '42501');
update pg_temp.fl_security_context set family_id = (public.create_family('測試家庭 A') ->> 'id')::uuid where actor = 'A';
select pg_temp.fl_assert(pg_catalog.jsonb_array_length(public.family_status() -> 'members') = 1, 'Creator must be the only initial member');
select pg_temp.fl_assert((public.family_status() #>> '{members,0,sharing}')::boolean = false, 'Sharing must default off');
select pg_temp.fl_expect_error('select public.create_family(''第二個家庭'')', '23505');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,80,pg_catalog.clock_timestamp())', '42501');
select public.set_display_name('爸爸測試');
select pg_temp.fl_assert(public.family_status() #>> '{members,0,display_name}' = '爸爸測試', 'Display-name change must affect the caller');
select public.set_sharing(true);
select public.publish_location(25.03, 121.56, 10, null, pg_catalog.clock_timestamp() - interval '20 seconds');
select pg_temp.fl_assert((public.family_status() #>> '{members,0,latitude}')::double precision = 25.03, 'Enabled caller must publish its own position');
select pg_temp.fl_assert(public.family_status() #> '{members,0,battery_level}' = 'null'::jsonb, 'Unknown battery must remain null');
select public.publish_location(24.0, 120.0, 20, 30, pg_catalog.clock_timestamp() - interval '60 seconds');
select pg_temp.fl_assert((public.family_status() #>> '{members,0,latitude}')::double precision = 25.03, 'Older fixes must not overwrite a newer position');
select pg_temp.fl_expect_error('select public.publish_location(''NaN''::double precision,121.56,10,80,pg_catalog.clock_timestamp())', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,''Infinity''::double precision,10,80,pg_catalog.clock_timestamp())', '22023');
select pg_temp.fl_expect_error('select public.publish_location(91,121.56,10,80,pg_catalog.clock_timestamp())', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,''NaN''::real,80,pg_catalog.clock_timestamp())', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,-1,80,pg_catalog.clock_timestamp())', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,101,pg_catalog.clock_timestamp())', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,80,pg_catalog.clock_timestamp()-interval ''6 minutes'')', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,80,pg_catalog.clock_timestamp()+interval ''2 minutes'')', '22023');
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,80,''infinity''::timestamptz)', '22023');
update pg_temp.fl_security_context set token = public.create_invite() where actor = 'A';
reset role;

-- Invitation storage contains only the hash, with a 24-hour lifetime.
select pg_temp.fl_assert(
    exists (select 1 from family_private.invites as i
            where i.token_hash = pg_catalog.sha256(pg_catalog.convert_to((select token from pg_temp.fl_security_context where actor = 'A'), 'UTF8'))
              and i.expires_at - i.created_at = interval '24 hours'), 'Invitation must be hashed and expire after 24 hours');

-- C's separate family cannot see A's identity or position.
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'C'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'C'), 'role', 'authenticated')::text, true);
update pg_temp.fl_security_context set family_id = (public.create_family('另一個測試家庭') ->> 'id')::uuid where actor = 'C';
select pg_temp.fl_assert(pg_catalog.jsonb_array_length(public.family_status() -> 'members') = 1, 'Separate family must see only itself');
select pg_temp.fl_assert(public.family_status() #>> '{members,0,user_id}' = (select user_id::text from pg_temp.fl_security_context where actor = 'C'), 'Separate family must not leak another member');
select pg_temp.fl_expect_error('select * from family_private.locations', '42501');

-- B sees nothing until explicitly redeeming A's invitation.
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'B'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'B'), 'role', 'authenticated')::text, true);
select pg_temp.fl_assert(public.family_status() -> 'family' = 'null'::jsonb, 'Unjoined account must see no family');
select public.join_family((select token from pg_temp.fl_security_context where actor = 'A'));
select pg_temp.fl_assert(pg_catalog.jsonb_array_length(public.family_status() -> 'members') = 2, 'Accepted invitation must add exactly one member');
select pg_temp.fl_assert(
    exists (select 1 from pg_catalog.jsonb_array_elements(public.family_status() -> 'members') as m(value)
            where m.value ->> 'user_id' = (select user_id::text from pg_temp.fl_security_context where actor = 'A')
              and (m.value ->> 'latitude')::double precision = 25.03), 'Same-family member must see A''s shared position');
select pg_temp.fl_assert(
    exists (select 1 from pg_catalog.jsonb_array_elements(public.family_status() -> 'members') as m(value)
            where m.value ->> 'user_id' = (select user_id::text from pg_temp.fl_security_context where actor = 'B')
              and m.value -> 'sharing' = 'false'::jsonb), 'Joining must not automatically enable B''s sharing');

-- D cannot replay B's consumed invitation.
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'D'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'D'), 'role', 'authenticated')::text, true);
select pg_temp.fl_expect_error('select public.join_family((select token from pg_temp.fl_security_context where actor=''A''))', '22023');
reset role;

-- Expiry and member cap are tested with controlled development fixtures.
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'A'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'A'), 'role', 'authenticated')::text, true);
update pg_temp.fl_security_context set extra_token = public.create_invite() where actor = 'A';
reset role;
update family_private.invites as i set created_at = pg_catalog.clock_timestamp() - interval '25 hours',
    expires_at = pg_catalog.clock_timestamp() - interval '1 hour'
where i.created_by = (select user_id from pg_temp.fl_security_context where actor = 'A');
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'D'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'D'), 'role', 'authenticated')::text, true);
select pg_temp.fl_expect_error('select public.join_family((select extra_token from pg_temp.fl_security_context where actor=''A''))', '22023');
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'A'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'A'), 'role', 'authenticated')::text, true);
update pg_temp.fl_security_context set extra_token = public.create_invite() where actor = 'A';
reset role;
insert into family_private.members(user_id, family_id, display_name)
select user_id, (select family_id from pg_temp.fl_security_context where actor = 'A'), actor
from pg_temp.fl_security_context where actor like 'fill%';
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'D'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'D'), 'role', 'authenticated')::text, true);
select pg_temp.fl_expect_error('select public.join_family((select extra_token from pg_temp.fl_security_context where actor=''A''))', '22023');
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'A'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'A'), 'role', 'authenticated')::text, true);
select pg_temp.fl_expect_error('select public.create_invite()', '22023');
reset role;
delete from family_private.members where user_id in (select user_id from pg_temp.fl_security_context where actor like 'fill%');

-- Pause removes the position and rejects further uploads until re-enabled.
set local role authenticated;
select public.set_sharing(false);
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,80,pg_catalog.clock_timestamp())', '42501');
reset role;
select pg_temp.fl_assert(not exists (select 1 from family_private.locations
    where user_id = (select user_id from pg_temp.fl_security_context where actor = 'A')), 'Pause must delete the stored position');
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'B'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'B'), 'role', 'authenticated')::text, true);
select pg_temp.fl_assert(
    exists (select 1 from pg_catalog.jsonb_array_elements(public.family_status() -> 'members') as m(value)
            where m.value ->> 'user_id' = (select user_id::text from pg_temp.fl_security_context where actor = 'A')
              and m.value -> 'latitude' = 'null'::jsonb and m.value -> 'sharing' = 'false'::jsonb),
    'Family must see sharing paused and no coordinates');

-- Leaving deletes positions/invitations and removes access; last departure
-- deletes the now-empty family. C's independent family is unaffected.
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'A'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'A'), 'role', 'authenticated')::text, true);
select public.leave_family();
select pg_temp.fl_assert(public.family_status() -> 'family' = 'null'::jsonb, 'Departed member must lose family access');
select public.set_sharing(false);
select pg_temp.fl_expect_error('select public.publish_location(25.03,121.56,10,80,pg_catalog.clock_timestamp())', '42501');
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'B'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'B'), 'role', 'authenticated')::text, true);
select pg_temp.fl_assert(pg_catalog.jsonb_array_length(public.family_status() -> 'members') = 1, 'Remaining member must keep its family');
select public.set_sharing(true);
select public.publish_location(24.1, 120.7, 15, 70, pg_catalog.clock_timestamp());
update pg_temp.fl_security_context set token = public.create_invite() where actor = 'B';
select public.leave_family();
reset role;
select pg_temp.fl_assert(not exists (select 1 from family_private.families
    where id = (select family_id from pg_temp.fl_security_context where actor = 'A')), 'Last departure must delete the empty family');
select pg_temp.fl_assert(not exists (select 1 from family_private.locations
    where user_id = (select user_id from pg_temp.fl_security_context where actor = 'B')), 'Leaving must delete the caller''s position');
set local role authenticated;
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'D'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'D'), 'role', 'authenticated')::text, true);
select pg_temp.fl_expect_error('select public.join_family((select token from pg_temp.fl_security_context where actor=''B''))', '22023');
select pg_catalog.set_config('request.jwt.claim.sub', (select user_id::text from pg_temp.fl_security_context where actor = 'C'), true);
select pg_catalog.set_config('request.jwt.claims', pg_catalog.jsonb_build_object('sub', (select user_id from pg_temp.fl_security_context where actor = 'C'), 'role', 'authenticated')::text, true);
select pg_temp.fl_assert(public.family_status() #>> '{family,name}' = '另一個測試家庭', 'Other family must remain isolated and intact');
reset role;

select 'PASS: roles, isolation, invite hashing/replay/expiry/cap, validation, pause, and departure checks' as result;
rollback;
