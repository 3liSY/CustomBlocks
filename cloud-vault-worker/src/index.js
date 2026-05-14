const CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "content-type",
};

const RATE_LIMIT_MAX    = 10;   // max shares per window
const RATE_LIMIT_WINDOW = 60;   // seconds
const META_PREFIX       = "meta:";
const RL_PREFIX         = "rl:";
const MARKET_PAGE_SIZE  = 20;

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

/** Returns true if the request is rate-limited; updates the KV counter. */
async function checkRateLimit(env, clientIp) {
  const key = RL_PREFIX + clientIp;
  const raw = await env.BLOCKS.get(key);
  const now = Math.floor(Date.now() / 1000);

  if (raw) {
    const rl = JSON.parse(raw);
    if (now < rl.reset) {
      if (rl.count >= RATE_LIMIT_MAX) return true; // limited
      await env.BLOCKS.put(key, JSON.stringify({ count: rl.count + 1, reset: rl.reset }), {
        expirationTtl: rl.reset - now + 1,
      });
      return false;
    }
    // window expired -- fall through to fresh window
  }

  // fresh window
  await env.BLOCKS.put(key, JSON.stringify({ count: 1, reset: now + RATE_LIMIT_WINDOW }), {
    expirationTtl: RATE_LIMIT_WINDOW + 5,
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
        endpoints: ["GET /", "POST /share", "GET /share/:hash", "GET /market"],
        rateLimit: `${RATE_LIMIT_MAX} shares / ${RATE_LIMIT_WINDOW}s per IP`,
      });
    }

    // POST /share
    if (request.method === "POST" && url.pathname === "/share") {
      // Rate-limit check
      const clientIp = ip(request);
      const limited = await checkRateLimit(env, clientIp);
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

      const stored = await env.BLOCKS.get(hash);
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
          const raw = await env.BLOCKS.get(k.name);
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

    return json({ error: "Not found" }, 404);
  },
};
