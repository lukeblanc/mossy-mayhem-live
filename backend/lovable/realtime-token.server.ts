/**
 * Server-only: mints short-lived OpenAI Realtime client secrets.
 * SECURITY: OPENAI_API_KEY is read from the server environment ONLY.
 */
import {
  CLIENT_SECRET_TTL_SECONDS,
  MOSSY_INSTRUCTIONS,
  OPENAI_CLIENT_SECRETS_URL,
  REALTIME_MODEL,
  REALTIME_VOICE,
  type MossyErrorCode,
} from "./mossy-config";

export type MintSuccess = {
  ok: true;
  value: string;
  expires_at: number;
  model: string;
  session_id: string | null;
};

export type MintFailure = {
  ok: false;
  error: MossyErrorCode;
  message: string;
  status: number;
  upstream_status?: number;
};

export async function mintRealtimeClientSecret(
  requestId: string,
): Promise<MintSuccess | MintFailure> {
  const apiKey = process.env["OPENAI_API_KEY"];
  if (!apiKey) {
    return {
      ok: false,
      error: "MISSING_SERVER_KEY",
      message: "Server is not configured to issue Realtime credentials.",
      status: 503,
    };
  }

  let upstream: Response;
  try {
    upstream = await fetch(OPENAI_CLIENT_SECRETS_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        expires_after: { anchor: "created_at", seconds: CLIENT_SECRET_TTL_SECONDS },
        session: {
          type: "realtime",
          model: REALTIME_MODEL,
          instructions: MOSSY_INSTRUCTIONS,
          output_modalities: ["audio"],
          audio: { output: { voice: REALTIME_VOICE } },
        },
      }),
    });
  } catch {
    console.error(`[realtime-token] rid=${requestId} upstream_network_error`);
    return {
      ok: false,
      error: "OPENAI_UPSTREAM_ERROR",
      message: "Could not reach the Realtime provider.",
      status: 502,
    };
  }

  if (!upstream.ok) {
    await upstream.text().catch(() => "");
    console.error(`[realtime-token] rid=${requestId} upstream_status=${upstream.status}`);
    const error: MossyErrorCode =
      upstream.status === 401 || upstream.status === 403
        ? "OPENAI_AUTH_FAILED"
        : upstream.status === 402 || upstream.status === 429
          ? "OPENAI_BILLING_OR_QUOTA"
          : "OPENAI_UPSTREAM_ERROR";
    return {
      ok: false,
      error,
      message:
        error === "OPENAI_AUTH_FAILED"
          ? "Server credential was rejected by the Realtime provider."
          : error === "OPENAI_BILLING_OR_QUOTA"
            ? "Realtime provider quota or billing limit reached."
            : "Realtime provider returned an error.",
      status: error === "OPENAI_BILLING_OR_QUOTA" ? 429 : 502,
      upstream_status: upstream.status,
    };
  }

  const json = (await upstream.json().catch(() => null)) as {
    value?: string;
    expires_at?: number;
    session?: { id?: string; model?: string };
  } | null;

  if (!json?.value) {
    console.error(`[realtime-token] rid=${requestId} upstream_malformed_response`);
    return {
      ok: false,
      error: "OPENAI_UPSTREAM_ERROR",
      message: "Realtime provider returned an unexpected response.",
      status: 502,
    };
  }

  return {
    ok: true,
    value: json.value,
    expires_at:
      json.expires_at ?? Math.floor(Date.now() / 1000) + CLIENT_SECRET_TTL_SECONDS,
    model: json.session?.model ?? REALTIME_MODEL,
    session_id: json.session?.id ?? null,
  };
}
