import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return Response.json({ error: "method_not_allowed" }, { status: 405, headers: cors });

  try {
    const authorization = req.headers.get("Authorization");
    if (!authorization?.startsWith("Bearer ")) {
      return Response.json({ error: "unauthorized" }, { status: 401, headers: cors });
    }

    const url = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !serviceKey) throw new Error("Server environment is not configured");

    const db = createClient(url, serviceKey, { auth: { persistSession: false } });
    const { data: userData, error: userError } = await db.auth.getUser(authorization.slice(7));
    if (userError || !userData.user) {
      return Response.json({ error: "unauthorized" }, { status: 401, headers: cors });
    }
    const { data: admin, error: adminError } = await db.from("license_admins")
      .select("user_id").eq("user_id", userData.user.id).maybeSingle();
    if (adminError) throw adminError;
    if (!admin) return Response.json({ error: "forbidden" }, { status: 403, headers: cors });

    const body = await req.json();
    const action = body?.action;
    if (action === "list") {
      const { data, error } = await db.from("licenses")
        .select("id,status,created_at,activated_at,last_check_at,note,device_hash")
        .order("created_at", { ascending: false }).limit(500);
      if (error) throw error;
      return Response.json({
        ok: true,
        licenses: (data ?? []).map(({ device_hash, ...item }) => ({ ...item, is_bound: Boolean(device_hash) })),
      }, { headers: cors });
    }

    if (typeof body?.id !== "string") {
      return Response.json({ error: "license_id_required" }, { status: 400, headers: cors });
    }
    if (action === "revoke") {
      const { data, error } = await db.from("licenses").update({ status: "revoked" })
        .eq("id", body.id).select("id,status").maybeSingle();
      if (error) throw error;
      if (!data) return Response.json({ error: "not_found" }, { status: 404, headers: cors });
      return Response.json({ ok: true, license: data }, { headers: cors });
    }
    if (action === "reset-device") {
      const { data, error } = await db.from("licenses")
        .update({ device_hash: null, activated_at: null, last_check_at: null })
        .eq("id", body.id).eq("status", "active").select("id,status").maybeSingle();
      if (error) throw error;
      if (!data) return Response.json({ error: "not_found_or_revoked" }, { status: 404, headers: cors });
      return Response.json({ ok: true, license: data }, { headers: cors });
    }
    return Response.json({ error: "unknown_action" }, { status: 400, headers: cors });
  } catch (_error) {
    return Response.json({ error: "server_error" }, { status: 500, headers: cors });
  }
});
