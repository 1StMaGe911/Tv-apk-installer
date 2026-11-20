import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function makeKey(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const chars = [...bytes].map((b) => alphabet[b % alphabet.length]);
  return "CYTV-" + [0, 4, 8, 12].map((start) => chars.slice(start, start + 4).join("")).join("-");
}

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
    const token = authorization.slice("Bearer ".length);
    const { data: userData, error: userError } = await db.auth.getUser(token);
    if (userError || !userData.user) {
      return Response.json({ error: "unauthorized" }, { status: 401, headers: cors });
    }

    const { data: admin, error: adminError } = await db
      .from("license_admins").select("user_id").eq("user_id", userData.user.id).maybeSingle();
    if (adminError) throw adminError;
    if (!admin) return Response.json({ error: "forbidden" }, { status: 403, headers: cors });

    const body = await req.json().catch(() => ({}));
    const note = typeof body.note === "string" ? body.note.slice(0, 300) : null;
    const key = makeKey();
    const { error: insertError } = await db.from("licenses").insert({
      key_hash: await sha256(key),
      note,
    });
    if (insertError) throw insertError;

    // Plaintext is returned once and is never stored in the database.
    return Response.json({ ok: true, key }, { status: 201, headers: cors });
  } catch (_error) {
    return Response.json({ error: "server_error" }, { status: 500, headers: cors });
  }
});
