# Summit demo: sensor to decision to explanation

Two live flows, about eight minutes in total. Each goes device → API → model → dashboard →
assistant. Everything shown is computed live. The simulator is a stand-in for real hardware and
labels its readings as simulated; use a real ESP32 wherever one is available.

## Before the session (15 minutes)

1. **Services.**
   ```bash
   cd IrrigaIQ-Backend && docker compose up -d            # PostgreSQL + pgvector
   cd ../IrrigaIQ-ML && .venv/bin/uvicorn irrigaiq_ml.api:app --port 8090
   cd ../IrrigaIQ-Backend && ./mvnw spring-boot:run       # assistant: see "Local or Claude" below
   cd ../IrrigaIQ-Web && npm run dev
   ```
   Check each service:
   - `http://localhost:8090/health` lists `equipment` and `irrigation`.
   - The backend log shows "Knowledge base: … embedded".
   - In the web app, Farm intelligence shows four model cards.
2. **Demo farm.** Go to Admin → Overview → *Create demo farm*, then pick it in the farm switcher.
3. **Devices and history.** Register the devices and load their history:
   ```bash
   cd IrrigaIQ-ML
   python tools/simulate_devices.py setup --email <you> --farm "Mazowe Valley Estate"
   python tools/simulate_devices.py backfill --days 14
   python tools/simulate_devices.py soil 34     # start from a comfortable reading
   python tools/simulate_devices.py normal
   ```
   Setup registers a probe on Block A, a meter on Block A's pump and a monitor on pump P-2.
   Backfill gives the meter and pump monitor enough history for their models; the anomaly check
   needs three days. With a real ESP32 instead, register it under **Devices & sensors** and flash
   `docs/esp32/irrigaiq_field_node.ino`.
4. **Screens.** Open **Irrigation** (Block A card), **Water** (Water meters), **Alerts** and the
   assistant panel (⌘J), each in its own tab.

### Local or Claude

For a fully offline stage, run the assistant on Llama in Docker:
`docker compose --profile ai up -d`, pull `llama3.1:8b` once, and set `ASSISTANT_PROVIDER=OLLAMA`.
It needs no internet, but on a Mac's CPU an answer takes about a minute, so ask the assistant
question before you start talking and let it stream while you explain. With `ANTHROPIC_API_KEY`
and internet, answers are faster and sharper.

## Flow 1: dry soil → irrigation recommended → "why?"

| Step | Do | Show and say |
| --- | --- | --- |
| 1 | `python tools/simulate_devices.py soil 23` (or dry the real probe) | "The probe in Block A reads 23 %. It posts to IrrigaIQ with its own key." |
| 2 | Refresh **Irrigation** | Block A turns *Crop stressed / Irrigate today*. "The FAO-56 water balance restarts from the probe reading: 68 % depleted, past the tomato's 32 % stress threshold. It works out depth, volume and run time, and schedules the run in the solar pump's hours." |
| 3 | Expand **Irrigation-need model** on the card | "A trained model gives a second opinion: high need, and it agrees. These are the readings that drove it. If it disagreed, IrrigaIQ would say so." |
| 4 | Ask the assistant: *Why does Field A need water?* | The tool chips show it reading the field and the knowledge base. "It isn't guessing. It calls the same services the dashboard uses, then explains them and cites FAO guidance." |

## Flow 2: unusual flow → possible anomaly → explanation

| Step | Do | Show and say |
| --- | --- | --- |
| 1 | `python tools/simulate_devices.py leak --lph 500 --hours 2` | "About 500 litres an hour is running through Block A's pump with no irrigation scheduled." |
| 2 | Open **Water → Water meters** (and **Alerts**) | *Possible anomaly*: about 400–500 L/h against about 1 L/h expected, with the possible causes listed. "The wording is deliberate: a meter shows that water moved, not why. It is a possible leak or irrigation-system issue, not a diagnosis." |
| 3 | Ask: *Is any water use abnormal right now?* then *How do I check it?* | The assistant gives observed against expected flow and the likely causes, then practical checks: controller, valves, walking the line, isolating sections. |
| 4 (optional) | Open **Infrastructure → P-2** | The pump monitor's three weeks of wear give about a 15–20 % chance of failure within 14 days: *Inspect the pump within 7 days.* The readings behind it sit next to the rule-based health score. |

## Questions people ask

- **"Is the AI deciding when to irrigate?"** No. The physically based water balance decides. The
  ML model is a second opinion and says when it disagrees. The assistant explains; it cannot
  change anything.
- **"What was the model trained on?"** Farm intelligence → Machine-learning models shows each
  model card. The irrigation model was trained on a public synthetic benchmark, and the anomaly
  and pump models on simulated faults. They should be retrained on real farm data, as the cards
  say.
- **"What if there's no internet?"** Weather falls back to climatology and the ML service runs
  locally. Only the assistant needs the Claude API. Everything else keeps working and says what
  it is missing.

## If something goes wrong

- *The meter shows "Learning".* It has under 72 hours of history: run `backfill`.
- *No anomaly after `leak`.* The check runs on new readings and every 15 minutes. Wait about five
  seconds and refresh. Alerts → *Re-check now* forces it.
- *The assistant says "Not set up yet".* `ANTHROPIC_API_KEY` is not set on the API. Present flow 1
  steps 1–3 and flow 2 steps 1–2 without it.
- *The ML service is down.* Cards show "model offline" and recommendations continue on the water
  balance. That is also a good point to make on stage.

## Reset

```bash
python tools/simulate_devices.py soil 34
python tools/simulate_devices.py normal
```

The anomaly alert clears itself once the unusual hours leave the six-hour window being judged.
