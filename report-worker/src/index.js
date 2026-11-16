/**
 * Worker mínimo para crear issues en GitHub desde Atril.
 *
 * Variables (wrangler secret put / [vars]):
 *   GITHUB_TOKEN     PAT fine-grained con "Issues: write" solo en el repo.
 *   ATRIL_KEY        clave compartida que envía la app (X-Atril-Key).
 *   GITHUB_REPO      p. ej. davidadrianrg/atril
 *   DAILY_LIMIT      tope global de issues por día (por defecto 30).
 *   ALLOW_ANONYMOUS  "true" para aceptar peticiones sin ATRIL_KEY configurada.
 *
 * La clave viaja dentro de un APK público, así que no es un secreto real: los
 * frenos de verdad son el límite por IP, el tope diario global y la
 * deduplicación. Sin ATRIL_KEY el endpoint se cierra salvo ALLOW_ANONYMOUS.
 * El texto acaba en una issue pública: las @menciones se neutralizan y el
 * diagnóstico va dentro de un bloque de código.
 */
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (!env.ATRIL_KEY && env.ALLOW_ANONYMOUS !== "true") {
      return json({ error: "endpoint not configured" }, 503);
    }
    if (env.ATRIL_KEY && request.headers.get("X-Atril-Key") !== env.ATRIL_KEY) {
      return json({ error: "forbidden" }, 403);
    }

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (env.RATE && !(await allowIp(env.RATE, ip))) {
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

    // El mismo reporte enviado dos veces apunta a la issue ya creada.
    const hash = await sha256(normalize(message) + "\n" + diagnostics);
    if (env.RATE) {
      const existing = await env.RATE.get(`dup:${hash}`);
      if (existing) return json({ url: existing, duplicate: true }, 200);
      if (!(await allowDaily(env.RATE, parseInt(env.DAILY_LIMIT || "30", 10)))) {
        return json({ error: "daily limit reached" }, 429);
      }
    }

    const title = "[Report] " + neutralize(message.split("\n")[0].slice(0, 80));
    const issueBody =
      `${neutralize(message)}\n\n---\n` +
      (diagnostics.trim()
        ? `<details><summary>Diagnostics</summary>\n\n~~~~text\n${fenceSafe(diagnostics)}\n~~~~\n\n</details>\n\n`
        : "") +
      `_Sent from Atril ${neutralize(appVersion)}_`;

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
    if (env.RATE && data.html_url) {
      await env.RATE.put(`dup:${hash}`, data.html_url, { expirationTtl: 7 * 86400 });
    }
    return json({ url: data.html_url }, 201);
  },
};

/** `@usuario` → `@​usuario`: se lee igual pero GitHub no notifica. */
export function neutralize(text) {
  return String(text).replace(/@(?=[A-Za-z0-9_-])/g, "@​");
}

/** Nada dentro del diagnóstico puede cerrar el bloque `~~~~`. */
export function fenceSafe(text) {
  return String(text).replace(/~{3,}/g, (m) => m.split("").join("​"));
}

function normalize(text) {
  return text.toLowerCase().replace(/\s+/g, " ").trim();
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function allowIp(kv, ip) {
  // 5 reportes por hora y IP.
  const key = `rl:${ip}:${Math.floor(Date.now() / 3600000)}`;
  return bump(kv, key, 5, 3600);
}

async function allowDaily(kv, limit) {
  const key = `day:${new Date().toISOString().slice(0, 10)}`;
  return bump(kv, key, Number.isFinite(limit) && limit > 0 ? limit : 30, 86400);
}

async function bump(kv, key, limit, ttl) {
  const current = parseInt((await kv.get(key)) || "0", 10);
  if (current >= limit) return false;
  await kv.put(key, String(current + 1), { expirationTtl: ttl });
  return true;
}

function json(payload, status) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
