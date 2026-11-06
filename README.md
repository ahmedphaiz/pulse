# Pulse — personal fitness tracker for Fitbit Air

A dark-mode phone app (installable web app) that reads your Fitbit Air data and adds:

- **Today**: steps ring, active calories, exercise minutes, live heart rate, today's heart-rate chart, sleep, 7-day steps
- **Workout detection**: spots sustained elevated heart rate and tells you "you're working out" or "you worked out 6:40–7:22, log it?"
- **Workouts**: log manually and save to Google Health (it then shows in the Google Health / Fitbit app). The Fitbit Air has no screen or buttons, so manual workouts are logged here.
- **Food**: basic logging with calories and macros, recent foods, and optional AI estimates ("2 eggs and toast")
- **Weight**: log weigh-ins, view a 7-day-average trend and progress to your goal weight, and save to Google Health
- **AI Coach**: a chat that sees your data (uses Claude via your own API key; gives basic automatic insights without one)

Everything you log stays on your phone (in the browser's storage). Use **Settings → Copy backup** now and then.

---

## How the data gets in

Fitbit Air → Google Health app (on your phone) → **Google Health API** → Pulse.

The old Fitbit Web API was switched off on 2 Nov 2026, so Pulse uses the new Google Health API with Google sign-in.

## Setup (about 15 minutes, done once)

### 1. Host the app (free)
The files must be served over HTTPS. The easiest options are:
- **GitHub Pages**: create a repo, upload all files in this folder, then Settings → Pages → deploy from `main`. Your URL will look like `https://<you>.github.io/pulse/`.
- **Netlify Drop**: drag this folder onto https://app.netlify.com/drop.

### 2. Create a Google Cloud project and enable the API
1. Go to https://console.cloud.google.com and create a project (for example "Pulse").
2. **APIs & Services → Library**: search for **Google Health API** and click **Enable**.
3. **APIs & Services → OAuth consent screen** (Google Auth Platform):
   - User type: **External**. App name: Pulse. Add your email.
   - **Data access / Scopes**: add the `googlehealth` scopes for activity_and_fitness, health_metrics_and_measurements, sleep and nutrition.
   - **Audience**: leave the app in **Testing** and add your own Gmail as a **test user**.
4. **Credentials → Create credentials → OAuth client ID**:
   - Type: **Web application**
   - Authorized JavaScript origins: your hosting origin, e.g. `https://<you>.github.io` (no path, no trailing slash)
   - Copy the **Client ID**.

> Google classes all Google Health API scopes as "restricted". An app used only by you and kept in Testing mode should work without Google's verification review. Sign-in shows a "Google hasn't verified this app" warning. Tap **Continue**. Testing-mode sign-ins may need repeating roughly weekly. If Google tightens this, the verification route is documented at developers.google.com/health.

### 3. Connect
1. Open your URL on your phone, then go to **Settings** (gear icon).
2. Paste the Client ID, tap **Connect Google Health**, and sign in with the Google account your Fitbit Air uses.
3. Demo mode turns off and your data loads. Pull fresh data with the ↻ button. The app also syncs when you reopen it.

### 4. Install on your phone
- **Android (Chrome)**: menu ⋮ → **Add to Home screen / Install app**
- **iPhone (Safari)**: Share → **Add to Home Screen**

### 5. (Optional) AI coach
1. Get an API key at https://console.anthropic.com and set a monthly spend limit.
2. Paste it in **Settings → AI coach**. The default model is `claude-haiku-5-5` (about $0.0005 per coach message). For more detailed plans, switch to `claude-sonnet-5-5` (about $0.01 per message).
3. The key is stored only on your phone and is sent only to Anthropic.

---

## How workout detection works
- Your workout zone is your resting HR + 38% of your heart-rate reserve, and at least 95 bpm. The dashed line on the chart shows it.
- 10+ minutes in that zone (short dips allowed) that doesn't overlap a logged workout shows up as **"You worked out …"**. The app estimates calories from heart rate, age, sex and weight.
- If your last 5 minutes of data are in the zone, it shows **"Looks like you're working out"**.
- Detection only sees data your Fitbit Air has already synced to Google Health, so "live" can lag a few minutes behind real time.
- Tap **Not a workout** to hide a false alarm (for example a stressful meeting or a hot day).

## Notes / limits
- Food is stored in the app only and is not sent to Google Health.
- Deleting a workout or weight in Pulse removes it from Pulse only, not from Google Health.
- The Google Health API is new and still changing. If a section fails to sync, **Settings** lists what failed and why.
- The coach gives general fitness guidance, not medical advice.
