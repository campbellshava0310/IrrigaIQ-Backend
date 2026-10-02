---
title: Irrigation scheduling with the soil water balance
sources:
  - title: "Crop evapotranspiration: guidelines for computing crop water requirements (FAO Irrigation and Drainage Paper 56)"
    publisher: FAO
    url: https://www.fao.org/4/x0490e/x0490e00.htm
---

## How much water a crop uses

A crop's daily water use is estimated from the reference evapotranspiration (ET0), the water a
well-watered grass surface would lose under that day's weather, multiplied by a crop coefficient
(Kc): ETc = Kc × ET0. ET0 is driven by radiation, temperature, humidity and wind, so hot, dry,
windy and sunny days raise it. Kc follows the crop's development: low after planting when the
ground is mostly bare, rising through the development stage, highest at mid-season when the
canopy is full, and falling again as the crop matures. Mid-season Kc values for full-cover field
crops are typically around 1.05 to 1.20.

## Root-zone water: TAW, RAW and depletion

The root zone holds a limited amount of water the crop can extract. Total available water (TAW)
is the water held between field capacity (what the soil holds after free drainage) and the
permanent wilting point, over the rooting depth. Sandy soils hold roughly 60 to 110 mm per metre
of depth, loams roughly 110 to 180 mm, and clays up to about 200 mm.

Only part of TAW can be used before the crop starts to suffer: the readily available water,
RAW = p × TAW. The depletion fraction p depends on the crop and is commonly between 0.3 and 0.6
(shallow-rooted, sensitive crops such as potatoes and onions sit at the low end). On very hot days
p is effectively lower, because the crop cannot draw water fast enough to keep up.

Depletion is how much of the root-zone water has been used since the soil was last at field
capacity. When depletion exceeds RAW the crop is under water stress: it closes its stomata,
transpires less, and yield potential falls.

## When to irrigate and how much

The water balance tracks depletion day by day: it rises with crop water use and falls with
effective rain and irrigation. Irrigation is due when depletion approaches RAW. Irrigating earlier
wastes water to deep drainage; irrigating later costs yield.

The net irrigation depth is the depletion to refill (in mm). The gross depth adds the losses of the
irrigation system: gross = net ÷ application efficiency. One millimetre over one hectare is ten
cubic metres. Run time is the gross depth divided by the system's application rate (mm per hour).

## Rain and the forecast

Not all rain is useful. Light showers are mostly lost to evaporation from wet leaves and soil, and
very heavy rain runs off or drains below the roots. When a sizeable rain is forecast with good
confidence in the next day or two, it is usually worth delaying an irrigation that is not yet
urgent, then re-checking the soil after the rain.

## Correcting the model with soil checks

A water balance drifts over time because rainfall, crop water use and applied volumes are all
estimated. Regular soil-moisture checks (a probe, a tensiometer or the feel method) reset it to
reality. IrrigaIQ restarts the balance from each recorded check.
