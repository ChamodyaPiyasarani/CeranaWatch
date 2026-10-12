# CeranaWatch AI server (keeps the Groq key out of the app)

The app never asks a beekeeper for a key. It talks to this small server, and the server adds the key.
Free Cloudflare account; no card needed for this size. (Names of buttons may look a little different.)

## Set it up once (about 10 minutes)
1. Go to dash.cloudflare.com and make a free account.
2. Open **Workers & Pages** → **Create** → **Create Worker**. Name it `cerana-ai` → **Deploy**.
3. Press **Edit code**. Delete what is there, paste everything from `cerana-ai-worker.js`, press **Deploy**.
4. Open the worker's **Settings** → **Variables and Secrets** → **Add**:
   - Type **Secret**, name `GROQ_KEY`, value = your Groq key (type it yourself).
   - Type **Secret**, name `APP_TOKEN`, value = any long word you make up (for example 20 random letters).
   Press **Deploy** / **Save**.
5. Copy the worker address, for example `https://cerana-ai.yourname.workers.dev`.

## Tell the app
Open `APP\secrets.properties` and fill:
```
ai.proxy.url=https://cerana-ai.yourname.workers.dev
ai.proxy.token=the same long word as APP_TOKEN
groq.key=
```
Then Android Studio → **File → Sync Project with Gradle Files** → **Run**.
In the app, More → Settings shows "Ready. The assistant comes with the app".

## Good to know
- The key stays in Cloudflare. If it leaks or you want a new one, change only the `GROQ_KEY` secret; the app does not change.
- The app token is inside the app, so it only stops casual misuse. Groq's free limits still apply.
- For a quick demo without the server you can put the key in `groq.key=` instead, but a key inside an app can be dug out of the APK, so then keep the app on your own phone.
