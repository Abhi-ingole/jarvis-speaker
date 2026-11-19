# 🤖 JARVIS by INGOVIQ

Bluetooth speaker la tuza swatahcha Jarvis banvnara Android app.

- Speaker connect zala ki Jarvis **"नमस्कार सर! जार्विस तयार आहे"** mhanto
- **"Jarvis"** mhanle ki jaga hoto (offline wake word)
- Marathi / Hindi / English / mix — **jya bhashet bolshil tyach bhashet uttar**
- YouTube Music control: *"Jarvis, Zingaat lav"*, *"Jarvis gaana badal"*, *"Jarvis pause kar"*, *"awaaz vadhav"*
- Gappa: *"Jarvis, aaj kay date aahe?"* — uttar dilyavar parat "Jarvis" na mhanta pudhe bolu shakto

## Kasa chalto

```
Speaker connect ─► "Jarvis" (Porcupine, offline)
                 ─► tuzi command record (gaana thoda vel pause)
                 ─► audio direct Gemini la ─► bhasha + uttar + action (JSON)
                 ─► Android TTS ne bolto ─► YouTube Music command
```

## 1. Keys (free)

| Key | Kuthe milel |
|---|---|
| Gemini API key | https://aistudio.google.com/apikey |
| Picovoice AccessKey | https://console.picovoice.ai (free account) |

Keys app madhech taka — code madhe / GitHub var nahi.

## 2. APK banva

**Option A — GitHub Actions (PC var kahi install nako)**
1. Ha folder navin GitHub repo madhe push kara
2. Repo → **Actions** → "Build Jarvis APK" → run zala ki **Artifacts → Jarvis-APK** download
3. Zip madhla `app-debug.apk` phone var install kara

**Option B — Android Studio**
Folder open kara → Run ▶ (phone USB debugging ne connect karun)

## 3. Phone var setup (ekdach)

1. App ughda → naav, Gemini key, Picovoice key taka
2. **🔊 Maza speaker nivad** → tuza Target speaker
3. Setup che 4 buttons dabun sagle ✅ kara
4. **Google Text-to-Speech** madhe Marathi voice download kara:
   Settings → Text-to-speech → Google → Install voice data → मराठी / हिन्दी
5. **▶ Jarvis suru kara** — ekda suru kela ki background madhe rahto. Speaker connect zala ki jaga hoto, disconnect zala ki mic band.

## Tips

- **Phone mic (default)** = gaana full quality. Phone speaker javal theva.
- **Speaker mic switch** = speaker madhun bolta yeta, pan Bluetooth "call mode" mule gaana mono / kami quality vajta.
- Android 14+ var app purna band asel tar speaker connect zalyavar notification yeil — tap kela ki Jarvis suru.
- Model badlaycha asel tar app madhe "Gemini model" field (default `gemini-flash-latest`).
