---
title: How IrrigaIQ's recommendations and models work
sources:
  - title: "IrrigaIQ model documentation (docs/irrigation-and-maintenance-models.md and IrrigaIQ-ML model cards)"
    publisher: IrrigaIQ
---

## The water balance recommendation

Each field's irrigation recommendation comes from the FAO-56 soil water balance. It uses the live
weather forecast (ET0 and rain), the crop and its growth stage, the soil type and the irrigation
system, and is corrected by soil checks and probe readings. It decides whether and when to irrigate
and calculates the depth, volume, run time, energy and cost. This is the physically grounded part of
the system and the basis for scheduling.

## The irrigation-need model

A machine-learning model (gradient-boosted trees) rates each field's irrigation need as Low, Medium
or High from soil moisture, temperature, wind, recent rainfall, growth stage and mulching, and lists
the factors that pushed its rating up or down. It was trained on a public synthetic benchmark
dataset, not on measurements from IrrigaIQ farms, and scored about 97 % balanced accuracy on held-out
data from that dataset. It is a second opinion beside the water balance: when the two disagree,
IrrigaIQ says so and suggests checking the soil, rather than silently picking one.

## The water anomaly detector

Hourly flow from each water meter is compared with that meter's own history, separately for hours
with scheduled irrigation and hours without, using robust statistics and an Isolation Forest; a
deviation must be extreme, or persist for several hours, before it is reported. On simulated faults
it caught every burst, blockage, valve left open and large leak, and 90 % of small leaks (3 to 6 % of
the pump's flow), with no false alarms; it has not yet been validated on real farm meters. It needs about three days of a meter's history
before it can judge. Its findings are possibilities (for example a possible leak or an irrigation
left running), never confirmed diagnoses.

## The pump failure-risk model

A gradient-boosting model with physics-based constraints (for example, higher vibration can never
lower the risk) estimates the probability that a pump fails within 14 days, from energy per cubic
metre and its trend, flow, pressure, vibration, motor temperature, running hours, service status and
age. It works with missing sensors and says which inputs were missing. It was trained on a simulated
pump fleet; on unseen simulated pumps it reached 99 % of the best achievable ranking accuracy. It
complements the rule-based health score, which explains each deduction.

## Limits to keep in mind

All three models need retraining on real IrrigaIQ data before they are relied on for decisions on
their own. Recommendations are decision support for the farm team, not automatic control, and
local agronomic or engineering advice takes precedence.
