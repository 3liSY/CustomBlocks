function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=UTF-8",
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return json({
        ok: true,
        service: "CustomBlocks Cloud Vault",
      });
    }

    if (request.method === "POST" && url.pathname === "/share") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "Invalid JSON body" }, 400);
      }

      const hash = typeof body.hash === "string" ? body.hash.trim() : "";
      if (!hash) return json({ error: "Missing hash" }, 400);
      if (!body.customId) return json({ error: "Missing customId" }, 400);

      await env.BLOCKS.put(hash, JSON.stringify(body));
      return json({
        ok: true,
        hash,
        code: `CB~${hash}`,
      }, 201);
    }

    if (request.method === "GET" && url.pathname.startsWith("/share/")) {
      const hash = decodeURIComponent(url.pathname.slice("/share/".length)).trim();
      if (!hash) return json({ error: "Missing hash" }, 400);

      const stored = await env.BLOCKS.get(hash);
      if (!stored) return json({ error: "Not found" }, 404);

      return new Response(stored, {
        status: 200,
        headers: {
          "content-type": "application/json; charset=UTF-8",
        },
      });
    }

    return json({ error: "Not found" }, 404);
  },
};
