/**
 * Mossy Mayhem Live — shared, NON-SECRET backend configuration.
 * Safe to import from client code: contains no credentials.
 */

export const BACKEND_VERSION = "1.0.0";

/** Current OpenAI Realtime model (audio output + image input). */
export const REALTIME_MODEL = "gpt-realtime";

/** Voice used for the narration. */
export const REALTIME_VOICE = "cedar";

/** Lifetime of the minted client secret, in seconds. */
export const CLIENT_SECRET_TTL_SECONDS = 600;

/** Official OpenAI endpoint that mints short-lived Realtime client secrets. */
export const OPENAI_CLIENT_SECRETS_URL =
  "https://api.openai.com/v1/realtime/client_secrets";

export const TOKEN_ENDPOINT_PATH = "/api/public/realtime-token";
export const HEALTH_ENDPOINT_PATH = "/api/public/health";

export const MOSSY_INSTRUCTIONS = `You are the narrator of "Mossy Mayhem Live", a deadpan comedy nature-documentary voice.
You receive a sequence of still images from a phone camera, in order, as the scene unfolds.
Narrate what you see as if it were rare wildlife footage: solemn, awestruck, and quietly absurd.
Rules:
- Speak in short, punchy documentary sentences. 1-3 sentences per image.
- Stay in character. Never mention images, models, prompts, or that you are an AI.
- Be affectionate, never mean. No slurs, no harassment, keep it broadcast-safe.
- Invent plausible taxonomy and behaviour ("the common desk-dwelling mug, at rest").
- If a new image continues the previous scene, continue the story rather than restarting.`;

export type MossyErrorCode =
  | "MISSING_SERVER_KEY"
  | "OPENAI_AUTH_FAILED"
  | "OPENAI_BILLING_OR_QUOTA"
  | "OPENAI_UPSTREAM_ERROR"
  | "RATE_LIMITED"
  | "BAD_REQUEST";
