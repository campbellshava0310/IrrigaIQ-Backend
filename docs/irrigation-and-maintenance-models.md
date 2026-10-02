# How IrrigaIQ decides: irrigation and maintenance models

IrrigaIQ gives useful advice before any sensor or trained model exists. Today every recommendation
comes from published agronomy (FAO-56, FAO-33) and transparent maintenance rules, driven by the
live weather forecast and what the farm team records. Three principles shape the design:

1. **Explainable.** Every recommendation carries the reasons behind it; every health score lists
   the factors that took points off and the action that would recover them.
2. **Calibratable.** Farmer observations (soil checks, condition checks, metered volumes) override
   the model where they exist, and confidence is reported honestly.
3. **Replaceable.** Each output carries `source` and `modelVersion` (`fao56-1`, `rules-1`). Sensor
   data and trained models replace the rules module by module behind the same API.

Code: `agronomy/WaterBalanceEngine`, `agronomy/CropCatalog`, `agronomy/SoilCatalog`,
`irrigation/IrrigationService`, `assets/AssetHealthEngine`, `alerts/AlertEvaluator`,
`water/WaterService`, `weather/*`. All engines are pure functions with unit tests.

## 1. Weather

| | |
| --- | --- |
| Provider | [Open-Meteo](https://open-meteo.com/en/docs) `/v1/forecast`, daily: `et0_fao_evapotranspiration`, `precipitation_sum`, `precipitation_probability_max`, `temperature_2m_max/min` |
| Window | Up to 92 past days and 16 forecast days, in the farm's time zone |
| Location | Farm coordinates, else the average of its fields' coordinates, else Harare (flagged "approximate") |
| Cache | `weather_daily` per farm and day; refreshed when older than 3 h; live data always replaces climatology, never the reverse. An hourly job prefetches active farms |
| Fallback | Deterministic seasonal climatology for Zimbabwe's highveld (dry-season ET₀ about 5–6.5 mm/day, rains November–March). Used when the provider is unreachable (10-minute back-off) or disabled (`WEATHER_PROVIDER=CLIMATOLOGY`); results are marked `live=false` and recommendations lose confidence |

Open-Meteo's reference evapotranspiration is the FAO-56 Penman-Monteith ET₀, so the water balance
consumes it directly.

## 2. Soil water balance (FAO-56 chapter 8)

A daily root-zone depletion balance per field, from planting (or up to 180 days back) through the
forecast:

```
Dr,i = Dr,i−1 − Peff,i − Ieff,i + ETa,i        (clamped to 0 … TAW; excess drains below the roots)
```

| Term | Definition |
| --- | --- |
| **TAW** (total available water, mm) | available water of the soil (mm/m) × rooting depth Zr (m) |
| **RAW** (readily available water) | p × TAW, with p = p_table + 0.04 (5 − ETc), clamped to 0.1–0.8 (FAO-56 Table 22 adjustment) |
| **ETc** (crop water use) | Kc × ET₀ |
| **Ks** (water-stress coefficient) | 1 while Dr ≤ RAW; otherwise (TAW − Dr) / ((1 − p) TAW) |
| **ETa** (actual use) | Ks × ETc |
| **Peff** (useful rain) | 0.8 × max(0, P − 2 mm): the first 2 mm is intercepted, 20% runs off or evaporates. Forecast rain is weighted by its probability (expected value) |
| **Ieff** (irrigation reaching the roots) | gross depth × application efficiency |

**Kc curve.** Kc_ini through the initial stage, rising linearly to Kc_mid across development, flat
through mid-season, falling linearly to Kc_end over the late season, then bare soil (0.2) after
harvest. **Rooting depth** grows linearly from Zr,min to Zr,max over the initial and development
stages.

**Crop parameters** (FAO-56 Tables 11, 12 and 22; FAO-33 Ky; typical Zimbabwe yields):

| Crop | Kc ini / mid / end | Stages (days) | Zr (m) | p | Ky | Typical yield (t/ha) |
| --- | --- | --- | --- | --- | --- | --- |
| Tomato | 0.60 / 1.15 / 0.70 | 30 / 40 / 40 / 25 | 0.25 → 0.80 | 0.40 | 1.05 | 50 |
| Maize | 0.30 / 1.20 / 0.35 | 20 / 35 / 40 / 30 | 0.30 → 1.00 | 0.55 | 1.25 | 8 |
| Wheat | 0.30 / 1.15 / 0.25 | 15 / 30 / 65 / 40 | 0.30 → 1.00 | 0.55 | 1.15 | 6 |
| Soybean | 0.40 / 1.15 / 0.50 | 20 / 30 / 35 / 25 | 0.30 → 0.80 | 0.50 | 0.85 | 3 |
| Potato | 0.50 / 1.15 / 0.75 | 25 / 30 / 45 / 30 | 0.25 → 0.50 | 0.35 | 1.10 | 35 |
| Tobacco | 0.50 / 1.10 / 0.80 | 20 / 30 / 40 / 30 | 0.30 → 0.80 | 0.50 | 0.90 | 2.8 (estimated) |

**Soil available water** (mm per metre of soil, FAO-56 Table 19 mid-range): sand 65, loamy sand
100, sandy loam 125, loam 155, silt loam 175, silt 185, sandy clay loam 140, clay loam 160, silty
clay loam 180, sandy clay 135, silty clay 170, clay 130. Fields without a soil type use loam.

**Soil checks** reset the balance on the day they are taken. The feel-and-appearance scale in the
apps follows the USDA NRCS guide: wet ≈ 5% depletion, moist ≈ 20%, slightly moist ≈ 40%, dry ≈
60%, very dry ≈ 85%. Irrigation already applied earlier that day is not added again.

## 3. From balance to recommendation

| Decision | When |
| --- | --- |
| `IRRIGATE_NOW` | Depletion has reached RAW, or will by the end of today. `STRESSED` when it is past RAW + half of (TAW − RAW) |
| `IRRIGATE_SOON` | The projection (including planned irrigation) crosses RAW within 2 days |
| `HOLD_FOR_RAIN` | Irrigation is due within 2 days but expected useful rain in the next 48 h covers ≥ 60% of the deficit (never when the crop is already stressed) |
| `SCHEDULED` | A planned irrigation falls within the next 48 h |
| `ON_TRACK` | Otherwise; shows the day the next irrigation will be needed |

- **Net depth** = the deficit on the irrigation day, capped per method at what one pass can
  deliver without runoff or deep drainage: drip uncapped, micro-sprinkler and sprinkler 35 mm,
  centre pivot 20 mm, furrow 80 mm, flood 100 mm. Larger deficits are refilled over several passes.
- **Gross depth** = net ÷ application efficiency (defaults: drip 0.90, micro 0.85, sprinkler 0.75,
  pivot 0.85, furrow 0.60, flood 0.50, or the field's measured value).
- **Volume** = gross (mm) × area (ha) × 10 m³. **Run time** = gross ÷ application rate.
- **Schedule.** Runs start inside the pump's operating window: solar hours for solar pumps, or
  the load-shedding-free window configured for grid pumps. Jobs longer than the window are split
  into several runs. Fields without a pump start at 06:00 (cool, low wind).
- **Energy** = pump rated power × run time; **cost** = energy × the farm's grid tariff (solar
  runs cost nothing).
- **Confidence.** High with live weather and a soil check in the last 7 days; medium with one of
  the two; low with neither. Default (unconfigured) irrigation systems are called out.

**Yield at risk** (FAO-33): 1 − Ya/Ym = Ky (1 − ΣETa / ΣETc) over the season so far, applied to
the field's target yield.

## 4. Infrastructure health (`rules-1`)

Each asset starts at 100 and loses points per factor; the score maps to risk: **low ≥ 80,
moderate ≥ 60, high ≥ 40, critical < 40**. A down asset is capped at 20.

| Factor | Rule | Impact |
| --- | --- | --- |
| Down / degraded | Reported by the team | −60 / −25 |
| Service overdue | Calendar interval or run-hours exceeded | −12 to −30, growing with lateness |
| Service due soon | Within 14 days, or less than 10% of the run-hour interval left | −4 |
| No service plan | No interval set | −5 |
| Energy per m³ drift (pumps) | Power ÷ flow vs the baseline (or rated power ÷ rated flow): ≥ +10% / ≥ +20% | −12 / −25 |
| Condition | Last inspection fair / poor | −10 / −25 |
| Vibration | Elevated / severe | −10 / −25 |
| Leak | Seen at the last check | −15 |
| Filter clogging | Differential pressure ≥ 0.5 / ≥ 0.8 bar | −15 / −25 |
| Water table falling (boreholes) | Level dropped ≥ 2 m across recent checks | −10 |
| Age | ≥ 80% / ≥ 100% of design life | −8 / −20 |
| No or stale checks | None, or none in 60 days | −5 |
| Open repairs | Corrective work orders still open | −8 each, up to −16 |

Energy per cubic metre is the earliest sign of pump wear: worn impellers and wear rings, or a
restricted suction, make the pump draw the same power for less water long before it fails.
Run-hour services are forecast from the pump's recent use, which the irrigation log accumulates.

## 5. Alerts

Evaluated after every change to a farm (after commit) and every 15 minutes. Each condition has a
stable key, so an alert is updated in place rather than duplicated, escalations re-open
acknowledged alerts, and alerts clear on their own when the condition goes away. A manual resolve
silences the same condition for 24 hours.

| Alert | Raised when |
| --- | --- |
| Irrigation required / crop water stress | A field is `IRRIGATE_NOW` / `STRESSED` |
| Heavy rain expected | ≥ 25 mm of expected rain in the forecast (hold irrigation, check drainage) |
| Asset down, failure risk, pump efficiency drop, filter clogging, leak, water level falling | The asset's worst health factor, severity by risk and criticality |
| Service overdue | Service overdue and no worse factor |
| Water allocation | A licensed source has drawn ≥ 90% of its annual allocation |

Any infrastructure alert becomes a prioritised work order (predictive, or corrective when the
asset is down) in one step.

## 6. Water accounting

- **Applied**: completed irrigation, gross.
- **Crop need**: Σ max(0, ETc − Peff) ÷ efficiency × area × 10, per field and day.
- **Calendar baseline**: the farm's previous practice (default 25 mm every 3 days on each planted
  field), which savings are measured against. Farms should set it to match reality.
- **Allocations**: applied water is attributed through each pump to the source that feeds it.

## 7. What sensors and trained models will change

| Today | With sensors and ML |
| --- | --- |
| Modelled depletion, reset by weekly soil checks | Soil-moisture probes measure it daily; the balance becomes a data-assimilation model |
| FAO-56 crop coefficients | Field-specific Kc and drainage learned from probe and flow data |
| Run time × rated rate | Flow meters give metered volumes; leaks show up as unexplained flow |
| Monthly condition checks | Flow, pressure and motor-current telemetry for anomaly detection and remaining-useful-life prediction |
| Rules-based alerts | Model alerts (`source: MODEL`) alongside the rules, compared before switching |

## References

- Allen, Pereira, Raes and Smith (1998). *Crop evapotranspiration: guidelines for computing crop
  water requirements.* FAO Irrigation and Drainage Paper 56, especially chapters 6 and 8.
  https://www.fao.org/4/x0490e/x0490e0b.htm, https://www.fao.org/4/x0490e/x0490e0e.htm
- Doorenbos and Kassam (1979). *Yield response to water.* FAO Irrigation and Drainage Paper 33.
- USDA NRCS. *Estimating soil moisture by feel and appearance.*
- Open-Meteo. *Weather forecast API documentation.* https://open-meteo.com/en/docs
- Research on predictive maintenance of irrigation pumps from energy and vibration indicators,
  e.g. PMC8951325.
- IRENA (2025). Renewable energy for agriculture in Zimbabwe; IWMI on solar irrigation in
  Zimbabwe (Makonde); reporting on ZESA load-shedding schedules. These shaped the solar and
  load-shedding pump windows.
- Commercial references for UX conventions: CropX, WiseConn, Netafim GrowSphere.

## Machine-learning layer

Three models from the IrrigaIQ-ML service sit beside the rules above; they never replace them.

- **Irrigation need** (`/ml/irrigation`) rates each planted field Low, Medium or High from soil
  moisture, forecast maximum temperature and wind, the last 90 days of rain scaled to a year,
  growth phase and mulch. Soil moisture comes from a probe reading under 6 hours old, converted
  through the probe's calibration, or otherwise from the water balance's depletion. Both are put
  on the model's scale by `agronomy/SoilMoistureScale` (15 % at wilting point, 40 % at field
  capacity). Agreement with the balance is judged by level: High goes with irrigate-soon,
  irrigate-now or stress; Low with anything short of irrigate-now; Medium with anything short of
  stress.
- **Water anomaly** (`/ml/water-anomalies`) compares each meter's hourly flow over the last six
  hours with its previous 21 days. It marks hours as scheduled when a logged or planned irrigation
  on a field the meter supplies overlaps them. Findings raise `WATER_ANOMALY` alerts in hedged
  wording.
- **Equipment failure risk** (`/ml/equipment-risk`) scores pumps from 7-day pump-monitor means
  where a monitor is fitted, and from the latest condition check and service and age records
  otherwise. Condition-check vibration levels map to ISO 10816-3 zone midpoints: 2.0, 5.8 and
  9.0 mm/s. A HIGH or CRITICAL risk raises an `ASSET_FAILURE_RISK` alert only when the rules have
  not already flagged the pump.

Model cards with evaluation and limitations are served at `GET /api/v1/ml/models`.

