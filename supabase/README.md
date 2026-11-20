# License backend

This directory contains the Supabase database schema and Edge Functions for device-bound licenses.

## Project setup

Project URL: `https://ttrxesailsvsesfddnco.supabase.co`

1. In Supabase Dashboard → SQL Editor, run the complete SQL from `migrations/202611200001_license_system.sql`.
2. Deploy the three functions in `functions/` using the Supabase CLI from the repository root:
   ```sh
   supabase login
   supabase link --project-ref ttrxesailsvsesfddnco
   supabase functions deploy activate
   supabase functions deploy check-license
   supabase functions deploy admin-create-license
   ```
3. The Supabase Edge Function environment provides `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY`. Never place the service-role key in the Android app, GitHub source, or a public web page.
4. In Authentication → Users, create the owner's admin account. Copy that user's UUID, then run this SQL in SQL Editor:
   ```sql
   insert into public.license_admins (user_id)
   values ('PASTE-ADMIN-USER-UUID-HERE');
   ```
   Only users listed in `license_admins` can create keys through the admin function.
5. The admin creation endpoint is:
   `POST https://ttrxesailsvsesfddnco.supabase.co/functions/v1/admin-create-license`
   It requires the signed-in admin user's access token in the Authorization header. It returns a newly generated key once; only its SHA-256 hash is stored.

## Endpoints

- `POST /functions/v1/activate` with JSON `{"key":"CYTV-....","deviceId":"installation-uuid"}`
- `POST /functions/v1/check-license` with the same JSON body
- `POST /functions/v1/admin-create-license` with optional JSON `{"note":"customer/order note"}`, authenticated as an admin

Activation binds a key atomically to one device identifier. The database stores only a SHA-256 hash of the key and device identifier. Revoking a license is performed by changing its status to `revoked` in the private SQL editor:

```sql
update public.licenses set status = 'revoked' where key_hash = 'SHA256_HASH';
```

A device transfer/reset must be an explicit admin operation after verifying the request. Do not clear `device_hash` casually: doing so permits another device to claim the key.

## Important

- This backend is a first implementation. The Android app still needs its activation screen, secure local state, 30-day offline grace period, and periodic silent refresh before the license gate is enabled in a release.
- A device identifier is an installation identifier, not an immutable hardware serial. Reinstalling or clearing app data may require an administrator to reset the binding.
- Public activation endpoints should be protected further with rate limiting/abuse monitoring before broad public distribution.
