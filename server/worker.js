// Bodai API: a tiny Cloudflare Worker for things that need more than one
// player, starting with "you beat X% of players today" on the daily puzzle.
// Runs on Cloudflare's free tier (100,000 requests a day) with a D1 database.
// No names or accounts: a solve is a random device ID, a level and a time.

const GAMES = new Set(["bodai-sudoku"]);
const ORIGINS = new Set(["https://sudoku.bodaijewels.com", "https://localhost", "http://127.0.0.1:8765"]);

function cors(request) {
  const origin = request.headers.get("Origin") || "";
  return {
    "Access-Control-Allow-Origin": ORIGINS.has(origin) ? origin : "https://sudoku.bodaijewels.com",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
  };
}

const json = (request, body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...cors(request) } });

// Dates come from the player's phone, so allow a day either side of ours
function dateOk(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
  const diff = Math.abs(Date.parse(`${date}T12:00:00Z`) - Date.now());
  return diff < 1.6 * 864e5;
}

async function standing(env, game, date, seconds) {
  const row = await env.DB.prepare(
    "SELECT COUNT(*) AS players, SUM(CASE WHEN seconds > ?1 THEN 1 ELSE 0 END) AS slower FROM solves WHERE game = ?2 AND date = ?3"
  )
    .bind(seconds, game, date)
    .first();
  const players = row?.players || 0;
  const slower = row?.slower || 0;
  // Out of everyone else, the share you were faster than
  const others = Math.max(players - 1, 0);
  return { players, slower, beat: others ? Math.round((slower / others) * 100) : null };
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request) });
    const url = new URL(request.url);

    // Record a daily solve (the first one per device counts) and say where it stands
    if (request.method === "POST" && url.pathname === "/daily") {
      let body;
      try {
        body = await request.json();
      } catch (e) {
        return json(request, { error: "bad json" }, 400);
      }
      const { game, date, player, level, seconds, mistakes } = body || {};
      const ok =
        GAMES.has(game) &&
        dateOk(date) &&
        typeof player === "string" &&
        player.length >= 8 &&
        player.length <= 64 &&
        typeof level === "string" &&
        level.length <= 12 &&
        Number.isInteger(seconds) &&
        seconds >= 20 &&
        seconds <= 86400 &&
        Number.isInteger(mistakes) &&
        mistakes >= 0 &&
        mistakes <= 99;
      if (!ok) return json(request, { error: "bad solve" }, 400);
      await env.DB.prepare(
        "INSERT OR IGNORE INTO solves (game, date, player, level, seconds, mistakes, at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)"
      )
        .bind(game, date, player, level, seconds, mistakes, Date.now())
        .run();
      const mine = await env.DB.prepare("SELECT seconds FROM solves WHERE game = ?1 AND date = ?2 AND player = ?3")
        .bind(game, date, player)
        .first();
      return json(request, await standing(env, game, date, mine?.seconds ?? seconds));
    }

    // Look up where a time stands, without recording anything
    if (request.method === "GET" && url.pathname === "/daily") {
      const game = url.searchParams.get("game");
      const date = url.searchParams.get("date");
      const seconds = Number(url.searchParams.get("seconds"));
      if (!GAMES.has(game) || !dateOk(date) || !(seconds > 0)) return json(request, { error: "bad query" }, 400);
      return json(request, await standing(env, game, date, seconds));
    }

    return json(request, { ok: true, name: "bodai-api" });
  },
};
