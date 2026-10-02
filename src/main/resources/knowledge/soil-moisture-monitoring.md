---
title: Soil-moisture probes and soil checks
sources:
  - title: "Crop evapotranspiration (FAO Irrigation and Drainage Paper 56)"
    publisher: FAO
    url: https://www.fao.org/4/x0490e/x0490e00.htm
  - title: "Estimating Soil Moisture by Feel and Appearance"
    publisher: USDA Natural Resources Conservation Service
---

## Types of soil-moisture sensor

Capacitance probes (including the low-cost capacitive sensors used with ESP32 boards) sense the
soil's dielectric permittivity, which rises with water content. They are cheap and robust but read
on their own scale: salinity, temperature, soil texture and poor soil contact all shift the
reading. Time-domain reflectometry (TDR) instruments are more accurate but more expensive.
Tensiometers measure how tightly the soil holds water (soil water tension) rather than how much
water there is, which relates directly to how hard the crop must work to extract it.

## Calibrating a probe in the field

Because a capacitive probe's percentage is not a true volumetric water content, growers calibrate
it in place. Irrigate until the soil is thoroughly wet, let it drain for a day or two, and note the
reading: that is the field-capacity (full) point. The refill point is the reading at which
irrigation should start, and the wilting point is where the crop can no longer extract water. In
IrrigaIQ, each probe can store its field-capacity and wilting-point readings; without them a
default scale of 40 % at field capacity and 15 % at wilting point is assumed.

## Where to install probes

Place probes where the crop's active roots are, typically in the upper half of the root zone, and a
second probe deeper if possible to see whether water is reaching the bottom of the root zone or
draining past it. Install them in a representative part of the field, not in a low spot, near a
leaking emitter or at the field edge. Under drip irrigation, place the probe within the wetted zone
near an emitter, as that is where the roots concentrate.

## Reading the trend, not the number

A single reading matters less than its trend. After irrigation the reading should rise quickly and
then fall steadily as the crop uses water. A reading that does not rise after irrigation suggests
the water is not reaching the probe (a blocked emitter, a leak upstream or the wrong zone). A
reading that keeps rising without irrigation suggests rain, a leak, or waterlogging. A sudden jump
or drop with no cause usually means a faulty or disturbed sensor.

## The feel method

Without a probe, a soil sample squeezed in the hand gives a useful estimate: dry, crumbly soil that
will not hold together is well past the refill point; soil that forms a ball and feels moist is
close to field capacity; soil that leaves water on the hand is wetter than field capacity. Take the
sample from the root zone, not the surface, and in several spots. The USDA NRCS publishes a guide
with photographs for each soil texture.
