/**
 * Shiro push gateway: Matrix Push Gateway API -> Firebase Cloud Messaging (HTTP v1).
 *
 * The homeserver calls POST /_matrix/push/v1/notify for each event that must wake a
 * device. Pushers are registered with format "event_id_only", so this worker only ever
 * sees room and event ids, never message content: the app fetches the event itself.
 *
 * Secrets / vars (never committed):
 * - FCM_SERVICE_ACCOUNT: the Firebase service account JSON (wrangler secret).
 * - ALLOWED_APP_IDS: comma separated Matrix pusher app ids accepted by this gateway.
 */

const NOTIFY_PATH = "/_matrix/push/v1/notify";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

let cachedToken = null; // { value, expiresAt }

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/" && request.method === "GET") {
      return new Response("shiro push gateway", { status: 200 });
    }
    if (url.pathname !== NOTIFY_PATH) {
      return json({ errcode: "M_UNRECOGNIZED", error: "Unknown endpoint" }, 404);
    }
    if (request.method !== "POST") {
      return json({ errcode: "M_UNRECOGNIZED", error: "Method not allowed" }, 405);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ errcode: "M_NOT_JSON", error: "Invalid JSON" }, 400);
    }

    const notification = body?.notification;
    const devices = Array.isArray(notification?.devices) ? notification.devices : [];
    if (devices.length === 0) {
      return json({ errcode: "M_BAD_JSON", error: "No devices" }, 400);
    }

    const allowed = new Set(
      (env.ALLOWED_APP_IDS || "").split(",").map((id) => id.trim()).filter(Boolean),
    );

    const serviceAccount = JSON.parse(env.FCM_SERVICE_ACCOUNT);
    const accessToken = await getAccessToken(serviceAccount);

    const rejected = [];
    await Promise.all(
      devices.map(async (device) => {
        const pushkey = device?.pushkey;
        if (!pushkey) return;
        if (!allowed.has(device.app_id)) {
          rejected.push(pushkey);
          return;
        }
        const ok = await sendToFcm(serviceAccount.project_id, accessToken, pushkey, notification);
        if (!ok) rejected.push(pushkey);
      }),
    );

    return json({ rejected });
  },
};

/** @return false when the token is permanently invalid (the homeserver drops the pusher). */
async function sendToFcm(projectId, accessToken, pushkey, notification) {
  const data = {
    event_id: notification.event_id ?? "",
    room_id: notification.room_id ?? "",
    unread: String(notification.counts?.unread ?? ""),
    prio: notification.prio ?? "high",
  };

  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token: pushkey,
          data,
          // High priority wakes the device out of Doze immediately.
          android: { priority: "HIGH", ttl: "86400s" },
        },
      }),
    },
  );

  if (response.ok) return true;

  const error = await response.json().catch(() => ({}));
  const status = error?.error?.status;
  const details = JSON.stringify(error?.error?.details ?? []);
  // Only unregistered or malformed tokens are rejected; transient errors keep the pusher.
  if (status === "NOT_FOUND" || details.includes("UNREGISTERED")) return false;
  if (status === "INVALID_ARGUMENT" && details.includes("token")) return false;
  console.warn("FCM send failed", response.status, status);
  return true;
}

async function getAccessToken(serviceAccount) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt - 60 > now) return cachedToken.value;

  const assertion = await signJwt(
    {
      iss: serviceAccount.client_email,
      scope: FCM_SCOPE,
      aud: TOKEN_URL,
      iat: now,
      exp: now + 3600,
    },
    serviceAccount.private_key,
  );

  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`OAuth token request failed: ${response.status}`);
  }
  const token = await response.json();
  cachedToken = { value: token.access_token, expiresAt: now + (token.expires_in ?? 3600) };
  return cachedToken.value;
}

async function signJwt(payload, privateKeyPem) {
  const header = { alg: "RS256", typ: "JWT" };
  const encoder = new TextEncoder();
  const unsigned = `${base64url(encoder.encode(JSON.stringify(header)))}.${base64url(
    encoder.encode(JSON.stringify(payload)),
  )}`;

  const pem = privateKeyPem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  return `${unsigned}.${base64url(new Uint8Array(signature))}`;
}

function base64url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
