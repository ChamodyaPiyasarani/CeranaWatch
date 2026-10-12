// CeranaWatch AI server (a Cloudflare Worker).
// The app sends its assistant request here. This server adds the Groq key, which is kept as a secret
// in Cloudflare, so the key is never inside the app and a beekeeper never sees or types it.
//
// Secrets to add in Cloudflare (Settings > Variables and Secrets):
//   GROQ_KEY  = your Groq API key
//   APP_TOKEN = any long word you make up; put the same word in APP\secrets.properties (ai.proxy.token)

const GROQ_URL = 'https://api.groq.com/openai/v1/chat/completions';
const MODELS = ['openai/gpt-oss-120b', 'openai/gpt-oss-20b'];   // the models the app uses
const PASS_ON = ['retry-after', 'x-ratelimit-remaining-tokens', 'x-ratelimit-reset-tokens',
                 'x-ratelimit-remaining-requests', 'x-ratelimit-reset-requests'];

function problem(status, message) {
  return new Response(JSON.stringify({ error: { message } }), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

export default {
  async fetch(request, env) {
    if (request.method !== 'POST') return problem(405, 'Use POST.');
    if (!env.GROQ_KEY) return problem(500, 'The server has no GROQ_KEY secret yet.');
    if (env.APP_TOKEN && request.headers.get('X-App-Token') !== env.APP_TOKEN) return problem(401, 'Not allowed.');

    const text = await request.text();
    if (text.length > 120000) return problem(413, 'The request is too large.');
    let body;
    try { body = JSON.parse(text); } catch (e) { return problem(400, 'The request is not JSON.'); }
    if (!MODELS.includes(body.model)) return problem(400, 'This model is not allowed on the server.');

    const answer = await fetch(GROQ_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + env.GROQ_KEY },
      body: JSON.stringify(body),
    });
    // the app uses these to wait politely when the free limit is near
    const headers = new Headers({ 'Content-Type': 'application/json' });
    for (const name of PASS_ON) {
      const value = answer.headers.get(name);
      if (value) headers.set(name, value);
    }
    return new Response(await answer.text(), { status: answer.status, headers });
  },
};
