-- License records are private. The app never receives direct table access.
create table if not exists public.licenses (
  id uuid primary key default gen_random_uuid(),
  key_hash text not null unique,
  status text not null default 'active' check (status in ('active','revoked')),
  device_hash text,
  created_at timestamptz not null default now(),
  activated_at timestamptz,
  last_check_at timestamptz,
  note text
);

create table if not exists public.license_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

alter table public.licenses enable row level security;
alter table public.license_admins enable row level security;
revoke all on public.licenses from anon, authenticated;
revoke all on public.license_admins from anon, authenticated;

-- Atomic bind: concurrent activations cannot claim one key for two devices.
create or replace function public.bind_license(p_key_hash text, p_device_hash text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  item public.licenses%rowtype;
begin
  select * into item from public.licenses
    where key_hash = p_key_hash and status = 'active'
    for update;

  if not found then
    return jsonb_build_object('ok', false, 'reason', 'invalid_or_revoked');
  end if;

  if item.device_hash is not null and item.device_hash <> p_device_hash then
    return jsonb_build_object('ok', false, 'reason', 'already_bound');
  end if;

  update public.licenses
    set device_hash = p_device_hash,
        activated_at = coalesce(activated_at, now()),
        last_check_at = now()
    where id = item.id;

  return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.check_license(p_key_hash text, p_device_hash text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  item public.licenses%rowtype;
begin
  select * into item from public.licenses
    where key_hash = p_key_hash
    for update;

  if not found or item.status <> 'active' then
    return jsonb_build_object('ok', false, 'reason', 'invalid_or_revoked');
  end if;

  if item.device_hash is null or item.device_hash <> p_device_hash then
    return jsonb_build_object('ok', false, 'reason', 'device_mismatch');
  end if;

  update public.licenses set last_check_at = now() where id = item.id;
  return jsonb_build_object('ok', true);
end;
$$;

revoke all on function public.bind_license(text,text) from public, anon, authenticated;
revoke all on function public.check_license(text,text) from public, anon, authenticated;
grant execute on function public.bind_license(text,text) to service_role;
grant execute on function public.check_license(text,text) to service_role;
