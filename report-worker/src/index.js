/**
 * Worker mínimo para crear issues en GitHub desde Atril.
 *
 * Variables (wrangler secret put):
 *   GITHUB_TOKEN  PAT fine-grained con "Issues: write" solo en el repo.
 *   ATRIL_KEY     clave compartida que envía la app (X-Atril-Key).
 *   GITHUB_REPO   p. ej. davidadrianrg/atril
 *
 * Sin ATRIL_KEY configurada el endpoint queda abierto: no lo hagas en un repo
 * público. El límite por IP evita spam básico.
 */
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (env.ATRIL_KEY && request.headers.get("X-Atril-Key") !== env.ATRIL_KEY) {
      return json({ error: "forbidden" }, 403);
    }

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (env.RATE && !(await allow(env.RATE, ip))) {
      return json({ error: "rate limited" }, 429);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "invalid json" }, 400);
    }
    const message = String(body.message || "").trim().slice(0, 4000);
    if (message.length < 5) return json({ error: "message too short" }, 400);
    const diagnostics = String(body.diagnostics || "").slice(0, 12000);
    const appVersion = String(body.appVersion || "").slice(0, 80);

    const title = "[Report] " + message.split("\n")[0].slice(0, 80);
    const issueBody =
      `${message}\n\n---\n${diagnostics}\n\n` +
      `_Sent from Atril ${appVersion}_`;

    const response = await fetch(
      `https://api.github.com/repos/${env.GITHUB_REPO}/issues`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.GITHUB_TOKEN}`,
          Accept: "application/vnd.github+json",
          "User-Agent": "Atril-Report-Worker",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          title,
          body: issueBody,
          labels: ["user-report"],
        }),
      },
    );
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      return json({ error: "github", detail: data.message || response.status }, 502);
    }
    return json({ url: data.html_url }, 201);
  },
};

async function allow(kv, ip) {
  // 5 reportes por hora y IP.
  const key = `rl:${ip}:${Math.floor(Date.now() / 3600000)}`;
  const current = parseInt((await kv.get(key)) || "0", 10);
  if (current >= 5) return false;
  await kv.put(key, String(current + 1), { expirationTtl: 3600 });
  return true;
}

function json(payload, status) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
