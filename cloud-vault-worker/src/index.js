const CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "content-type",
};

const RATE_LIMIT_MAX    = 10;   // max shares per window
const RATE_LIMIT_WINDOW = 60;   // seconds
const META_PREFIX       = "meta:";
const RL_PREFIX         = "rl:";
const PACK_RL_PREFIX    = "rl-pack:";
const PACK_RL_MAX       = 20;   // max pack uploads per window (generous for legit server use)
const PACK_RL_WIN       = 60;   // seconds
const MARKET_PAGE_SIZE  = 20;
const PACK_TTL          = 7 * 86400; // R.32: 7-day TTL so idle servers keep delivering
const AI_RL_PREFIX      = "rl-ai:";
const AI_RL_MAX         = 5;   // max AI requests per player per window
const AI_RL_WIN         = 60;  // seconds

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=UTF-8",
      ...CORS_HEADERS,
      ...extra,
    },
  });
}

function ip(request) {
  return (
    request.headers.get("cf-connecting-ip") ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    "unknown"
  );
}

/**
 * Returns true if the request is rate-limited; updates the KV counter.
 * key       — full KV key (caller builds it: prefix + clientIp)
 * max       — max requests allowed in window
 * windowSecs — window length in seconds
 */
async function checkRateLimit(env, key, max, windowSecs) {
  let raw;
  try { raw = await env.BLOCKS.get(key); } catch { return false; } // 7.50 — KV errors don't block requests
  const now = Math.floor(Date.now() / 1000);

  if (raw) {
    const rl = JSON.parse(raw);
    if (now < rl.reset) {
      if (rl.count >= max) return true; // limited
      await env.BLOCKS.put(key, JSON.stringify({ count: rl.count + 1, reset: rl.reset }), {
        expirationTtl: rl.reset - now + 1,
      });
      return false;
    }
    // window expired — fall through to fresh window
  }

  // fresh window
  await env.BLOCKS.put(key, JSON.stringify({ count: 1, reset: now + windowSecs }), {
    expirationTtl: windowSecs + 5,
  });
  return false;
}

export default {
  async fetch(request, env) {
    // Preflight
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    // GET /
    if (request.method === "GET" && url.pathname === "/") {
      return json({
        ok: true,
        service: "CustomBlocks Cloud Vault v2",
        endpoints: ["GET /", "POST /share", "GET /share/:hash", "GET /market", "POST /pack", "GET /pack.zip"],
        rateLimit: `${RATE_LIMIT_MAX} shares / ${RATE_LIMIT_WINDOW}s per IP`,
      });
    }

    // POST /pack — upload resource pack ZIP (authenticated by shared secret)
    if (request.method === "POST" && url.pathname === "/pack") {
      // R.30: timing-safe secret comparison to prevent timing-oracle attacks
      const authHeader = request.headers.get("x-pack-secret");
      if (!authHeader || !env.PACK_SECRET) {
        return json({ error: "Unauthorized" }, 401);
      }
      const encoder = new TextEncoder();
      const authBytes = encoder.encode(authHeader);
      const secretBytes = encoder.encode(env.PACK_SECRET);
      const authed = authBytes.byteLength === secretBytes.byteLength
        && crypto.subtle.timingSafeEqual(authBytes, secretBytes);
      if (!authed) {
        return json({ error: "Unauthorized" }, 401);
      }

      // R.31: rate-limit pack uploads per IP (prevents abuse from leaked secrets)
      const clientIp = ip(request);
      const limited = await checkRateLimit(env, PACK_RL_PREFIX + clientIp, PACK_RL_MAX, PACK_RL_WIN);
      if (limited) {
        return json(
          { error: "Rate limit exceeded", retryAfter: PACK_RL_WIN },
          429,
          { "retry-after": String(PACK_RL_WIN) }
        );
      }

      const body = await request.arrayBuffer();
      if (body.byteLength > 20 * 1024 * 1024) {
        return json({ error: "Pack too large" }, 413);
      }
      const hash = request.headers.get("x-pack-hash") || "latest";
      // R.32: 7-day TTL so the pack survives server downtime up to a week
      await env.BLOCKS.put("pack:latest", body, {
        expirationTtl: PACK_TTL,
        metadata: { hash, updatedAt: new Date().toISOString() },
      });
      return json({ ok: true, url: `https://${url.hostname}/pack.zip` }, 201);
    }

    // GET /pack.zip — serve resource pack to Minecraft clients
    if (request.method === "GET" && url.pathname === "/pack.zip") {
      let data;
      try { data = await env.BLOCKS.getWithMetadata("pack:latest", { type: "arrayBuffer" }); }
      catch { return json({ error: "Storage unavailable" }, 503); } // 7.50
      if (!data || !data.value) return new Response("No pack uploaded yet", { status: 404 });
      return new Response(data.value, {
        status: 200,
        headers: {
          "content-type": "application/zip",
          "content-disposition": "attachment; filename=\"pack.zip\"",
          "x-pack-hash": data.metadata?.hash || "",
          ...CORS_HEADERS,
        },
      });
    }

    // POST /share
    if (request.method === "POST" && url.pathname === "/share") {
      // Rate-limit check
      const clientIp = ip(request);
      const limited = await checkRateLimit(env, RL_PREFIX + clientIp, RATE_LIMIT_MAX, RATE_LIMIT_WINDOW);
      if (limited) {
        return json(
          { error: "Rate limit exceeded", retryAfter: RATE_LIMIT_WINDOW },
          429,
          { "retry-after": String(RATE_LIMIT_WINDOW) }
        );
      }

      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "Invalid JSON body" }, 400);
      }

      const hash = typeof body.hash === "string" ? body.hash.trim() : "";
      if (!hash)         return json({ error: "Missing hash" }, 400);
      if (!body.customId) return json({ error: "Missing customId" }, 400);

      const code = `CB~${hash}`;
      const createdAt = new Date().toISOString();

      // Store full payload (30-day TTL)
      await env.BLOCKS.put(hash, JSON.stringify(body), { expirationTtl: 30 * 86400 });

      // Store lightweight listing metadata (30-day TTL)
      const meta = {
        hash,
        code,
        customId:    body.customId,
        displayName: typeof body.displayName === "string" ? body.displayName.substring(0, 64) : body.customId,
        createdAt,
      };
      await env.BLOCKS.put(META_PREFIX + hash, JSON.stringify(meta), { expirationTtl: 30 * 86400 });

      return json({ ok: true, hash, code }, 201);
    }

    // GET /share/:hash
    if (request.method === "GET" && url.pathname.startsWith("/share/")) {
      const hash = decodeURIComponent(url.pathname.slice("/share/".length)).trim();
      if (!hash) return json({ error: "Missing hash" }, 400);

      let stored;
      try { stored = await env.BLOCKS.get(hash); }
      catch { return json({ error: "Storage unavailable" }, 503); } // 7.50
      if (!stored) return json({ error: "Not found" }, 404);

      return new Response(stored, {
        status: 200,
        headers: {
          "content-type": "application/json; charset=UTF-8",
          ...CORS_HEADERS,
        },
      });
    }

    // GET /market
    if (request.method === "GET" && url.pathname === "/market") {
      const cursor = url.searchParams.get("cursor") || undefined;
      const limit  = Math.min(parseInt(url.searchParams.get("limit") || String(MARKET_PAGE_SIZE), 10), 50);

      const listed = await env.BLOCKS.list({
        prefix: META_PREFIX,
        limit,
        cursor,
      });

      const entries = await Promise.all(
        listed.keys.map(async (k) => {
          let raw;
          try { raw = await env.BLOCKS.get(k.name); } catch { return null; } // 7.50
          if (!raw) return null;
          try { return JSON.parse(raw); } catch { return null; }
        })
      );

      const items = entries
        .filter(Boolean)
        .sort((a, b) => (b.createdAt || "").localeCompare(a.createdAt || ""));

      return json({
        ok: true,
        items,
        nextCursor: listed.list_complete ? null : listed.cursor,
        total: items.length,
      });
    }

    // POST /ai — Groq chat proxy; authenticated by CB_SERVER_TOKEN, rate-limited per playerUuid
    if (request.method === "POST" && url.pathname === "/ai") {
      // Timing-safe token validation (same pattern as /pack)
      const authHeader = request.headers.get("x-cb-token");
      if (!authHeader || !env.CB_SERVER_TOKEN) {
        return json({ error: "Unauthorized" }, 401);
      }
      const encoder = new TextEncoder();
      const authBytes   = encoder.encode(authHeader);
      const secretBytes = encoder.encode(env.CB_SERVER_TOKEN);
      const authed = authBytes.byteLength === secretBytes.byteLength
        && crypto.subtle.timingSafeEqual(authBytes, secretBytes);
      if (!authed) {
        return json({ error: "Unauthorized" }, 401);
      }

      let body;
      try { body = await request.json(); } catch {
        return json({ error: "Invalid JSON body" }, 400);
      }

      const question   = typeof body.question   === "string" ? body.question.trim()   : "";
      const playerUuid = typeof body.playerUuid === "string" ? body.playerUuid.trim() : "";
      if (!question)   return json({ error: "Missing question" }, 400);
      if (!playerUuid) return json({ error: "Missing playerUuid" }, 400);

      // Per-player rate limit (5 req / 60 s)
      const limited = await checkRateLimit(env, AI_RL_PREFIX + playerUuid, AI_RL_MAX, AI_RL_WIN);
      if (limited) {
        return json(
          { error: "Rate limit exceeded", retryAfter: AI_RL_WIN },
          429,
          { "retry-after": String(AI_RL_WIN) }
        );
      }

      // Forward to Groq — GROQ_API_KEY is exclusively an encrypted Cloudflare secret
      let groqResp;
      try {
        groqResp = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "authorization": "Bearer " + env.GROQ_API_KEY,
          },
          body: JSON.stringify({
            model: "llama3-8b-8192",
            messages: [
              {
                role: "system",
                content: "You are a helpful assistant for a Minecraft server mod called CustomBlocks. Answer concisely in plain text (no markdown).",
              },
              { role: "user", content: question },
            ],
            max_tokens: 300,
          }),
        });
      } catch (e) {
        return json({ error: "AI service unreachable: " + e.message }, 502);
      }

      if (!groqResp.ok) {
        const errText = await groqResp.text().catch(() => "");
        return json({ error: "Groq error " + groqResp.status, detail: errText }, 502);
      }

      let groqData;
      try { groqData = await groqResp.json(); } catch {
        return json({ error: "Invalid response from Groq" }, 502);
      }

      const reply = groqData?.choices?.[0]?.message?.content?.trim() || "(no response)";
      return json({ reply });
    }

    return json({ error: "Not found" }, 404);
  },
};
