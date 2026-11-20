import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") {
    return Response.json({ error: "method_not_allowed" }, { status: 405, headers: cors });
  }

  try {
    const authorization = req.headers.get("Authorization");
    if (!authorization?.startsWith("Bearer ")) {
      return Response.json({ error: "unauthorized" }, { status: 401, headers: cors });
    }

    const url = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !serviceKey) throw new Error("Server environment is not configured");

    const db = createClient(url, serviceKey, { auth: { persistSession: false } });
    const token = authorization.slice("Bearer ".length);
    const { data: userData, error: userError } = await db.auth.getUser(token);
    if (userError || !userData.user) {
      return Response.json({ error: "unauthorized" }, { status: 401, headers: cors });
    }

    const { data: admin, error: adminError } = await db
      .from("license_admins")
      .select("user_id")
      .eq("user_id", userData.user.id)
      .maybeSingle();
    if (adminError) throw adminError;
    if (!admin) return Response.json({ error: "forbidden" }, { status: 403, headers: cors });

    const body = await req.json().catch(() => ({}));
    const action = body.action;

    if (action === "list") {
      const { data, error } = await db
        .from("licenses")
        .select("id,status,device_hash,created_at,activated_at,last_check_at,note")
        .order("created_at", { ascending: false });
      if (error) throw error;

      const licenses = (data ?? []).map((item) => ({
        id: item.id,
        status: item.status,
        activated: item.device_hash !== null,
        created_at: item.created_at,
        activated_at: item.activated_at,
        last_check_at: item.last_check_at,
        note: item.note,
      }));
      return Response.json({ ok: true, licenses }, { headers: cors });
    }

    if (action !== "revoke" && action !== "reset-device") {
      return Response.json({ error: "invalid_action" }, { status: 400, headers: cors });
    }

    if (typeof body.id !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(body.id)) {
      return Response.json({ error: "invalid_license_id" }, { status: 400, headers: cors });
    }

    const update = action === "revoke"
      ? { status: "revoked" }
      : { device_hash: null, activated_at: null, last_check_at: null };

    const { data, error } = await db
      .from("licenses")
      .update(update)
      .eq("id", body.id)
      .select("id,status")
      .maybeSingle();

    if (error) throw error;
    if (!data) return Response.json({ error: "license_not_found" }, { status: 404, headers: cors });

    return Response.json({ ok: true, license: data }, { headers: cors });
  } catch (_error) {
    return Response.json({ error: "server_error" }, { status: 500, headers: cors });
  }
});
