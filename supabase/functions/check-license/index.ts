import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value.trim().toUpperCase());
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return Response.json({ error: "method_not_allowed" }, { status: 405, headers: cors });

  try {
    const { key, deviceId } = await req.json();
    if (typeof key !== "string" || typeof deviceId !== "string" ||
        key.trim().length < 12 || deviceId.length < 16 || deviceId.length > 200) {
      return Response.json({ ok: false, reason: "invalid_request" }, { status: 400, headers: cors });
    }

    const url = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !serviceKey) throw new Error("Server environment is not configured");

    const db = createClient(url, serviceKey, { auth: { persistSession: false } });
    const { data, error } = await db.rpc("check_license", {
      p_key_hash: await sha256(key),
      p_device_hash: await sha256(deviceId),
    });
    if (error) throw error;

    return Response.json(data, { status: data?.ok ? 200 : 403, headers: cors });
  } catch (_error) {
    return Response.json({ ok: false, reason: "server_error" }, { status: 500, headers: cors });
  }
});
