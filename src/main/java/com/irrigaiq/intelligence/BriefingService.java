package com.irrigaiq.intelligence;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.irrigaiq.agronomy.WaterBalanceEngine.Decision;
import com.irrigaiq.agronomy.WaterBalanceEngine.Recommendation;
import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.assets.AssetHealthEngine.Factor;
import com.irrigaiq.assets.AssetViews.AssetHealthSnapshot;
import com.irrigaiq.assets.RiskLevel;
import com.irrigaiq.common.text.HumanText;
import com.irrigaiq.farm.Farm;
import com.irrigaiq.farm.FarmService;
import com.irrigaiq.irrigation.IrrigationService.FieldWaterState;
import com.irrigaiq.security.AuthenticatedUser;
import com.irrigaiq.water.WaterService.WaterSummary;
import com.irrigaiq.weather.WeatherDay;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily farm briefing, written from verified platform data only. Every sentence is built from
 * a specific figure and links back to where it came from; a future LLM layer may rephrase the
 * text but not introduce facts.
 */
@Service
public class BriefingService {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private final FarmService farms;

	private final FarmSnapshot.Assembler assembler;

	private final Clock clock;

	public BriefingService(FarmService farms, FarmSnapshot.Assembler assembler, Clock clock) {
		this.farms = farms;
		this.assembler = assembler;
		this.clock = clock;
	}

	public enum Tone {

		GOOD, WATCH, ACTION

	}

	/** @param linkType FIELD, ASSET, WATER or WEATHER, so clients can deep-link the statement */
	public record Item(String text, Tone tone, String linkType, UUID linkId) {
	}

	public record Section(String key, String title, Tone tone, List<Item> items) {
	}

	public record Briefing(UUID farmId, String farmName, LocalDate date, Instant generatedAt, String headline,
			List<Section> sections, String method, String disclaimer) {
	}

	@Transactional(readOnly = true)
	public Briefing briefing(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.requireAccessible(farmId, user);
		FarmSnapshot snapshot = assembler.assemble(farm);
		List<Section> sections = List.of(irrigation(snapshot), weather(snapshot), water(snapshot),
				infrastructure(snapshot), yieldProtection(snapshot));
		return new Briefing(farm.getId(), farm.getName(), snapshot.today(), clock.instant(), headline(snapshot),
				sections.stream().filter((s) -> !s.items().isEmpty()).toList(), "RULES",
				"Written by IrrigaIQ's rules from verified farm data: the FAO-56 water balance, live weather, "
						+ "irrigation logs and inspections. An AI narrative will rephrase it later, grounded in the same figures.");
	}

	private static String headline(FarmSnapshot s) {
		long needWater = s.fields()
			.stream()
			.filter((f) -> f.recommendation().decision() == Decision.IRRIGATE_NOW)
			.count();
		long stressed = s.fields().stream().filter((f) -> f.recommendation().status() == WaterStatus.STRESSED).count();
		long assetsAtRisk = s.assets()
			.stream()
			.filter((a) -> a.health().risk() == RiskLevel.HIGH || a.health().risk() == RiskLevel.CRITICAL)
			.count();
		List<String> parts = new ArrayList<>();
		if (stressed > 0) {
			parts.add(stressed + (stressed == 1 ? " field is" : " fields are") + " water-stressed");
		}
		else if (needWater > 0) {
			parts.add(needWater + (needWater == 1 ? " field needs" : " fields need") + " water today");
		}
		if (assetsAtRisk > 0) {
			parts.add(assetsAtRisk + (assetsAtRisk == 1 ? " asset needs" : " assets need") + " maintenance attention");
		}
		if (parts.isEmpty()) {
			return "All fields are on track and the water system is healthy.";
		}
		String text = String.join(", and ", parts) + ".";
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static Section irrigation(FarmSnapshot s) {
		List<Item> items = new ArrayList<>();
		for (FieldWaterState f : s.fields()) {
			Recommendation rec = f.recommendation();
			String name = f.field().getName();
			UUID id = f.field().getId();
			switch (rec.decision()) {
				case IRRIGATE_NOW -> items.add(new Item(String.format(Locale.ENGLISH,
						"%s: irrigate today, about %.0f mm (%.0f m³, %s) starting %s (%s).", name,
						rec.grossDepthMm(), rec.volumeM3(), HumanText.duration(rec.durationMinutes()), f.schedule().startTime(),
						f.schedule().windowLabel().toLowerCase(Locale.ENGLISH)),
						Tone.ACTION, "FIELD", id));
				case IRRIGATE_SOON -> items.add(new Item(String.format(Locale.ENGLISH,
						"%s: plan about %.0f mm for %s, before the crop reaches stress.", name, rec.grossDepthMm(),
						DAY.format(rec.irrigateOn())), Tone.WATCH, "FIELD", id));
				case HOLD_FOR_RAIN -> items.add(new Item(String.format(Locale.ENGLISH,
						"%s: hold irrigation, about %.0f mm of useful rain is expected in the next 48 h.", name,
						rec.expectedRainMm()), Tone.GOOD, "FIELD", id));
				case SCHEDULED -> items.add(new Item(name + ": irrigation already planned. Confirm it once it has run.",
						Tone.GOOD, "FIELD", id));
				case ON_TRACK -> items.add(new Item((rec.irrigateOn() != null)
						? String.format(Locale.ENGLISH, "%s: on track; next irrigation around %s.", name,
								DAY.format(rec.irrigateOn()))
						: name + ": soil water is sufficient for the next 7 days.", Tone.GOOD, "FIELD", id));
				default -> {
				}
			}
		}
		return new Section("irrigation", "Irrigation today", worst(items), items);
	}

	private static Section weather(FarmSnapshot s) {
		List<WeatherDay> days = s.weather().days();
		List<Item> items = new ArrayList<>();
		if (days.isEmpty()) {
			return new Section("weather", "Weather", Tone.GOOD, items);
		}
		double maxEt0 = days.stream().limit(3).mapToDouble((d) -> nz(d.et0Mm())).max().orElse(0);
		double minEt0 = days.stream().limit(3).mapToDouble((d) -> nz(d.et0Mm())).min().orElse(0);
		WeatherDay wettest = days.stream()
			.max((a, b) -> Double.compare(expectedRain(a), expectedRain(b)))
			.orElse(null);
		String et0Range = (maxEt0 - minEt0 < 0.1) ? String.format(Locale.ENGLISH, "about %.1f", maxEt0)
				: String.format(Locale.ENGLISH, "%.1f–%.1f", minEt0, maxEt0);
		items.add(new Item(String.format(Locale.ENGLISH,
				"Next 3 days: crop water demand %s (reference ET %s mm/day), highs up to %.0f °C.",
				(maxEt0 >= 5.5) ? "high" : (maxEt0 >= 3.5) ? "moderate" : "low", et0Range,
				days.stream().limit(3).mapToDouble((d) -> nz(d.tempMaxC())).max().orElse(0)),
				(maxEt0 >= 6) ? Tone.WATCH : Tone.GOOD, "WEATHER", null));
		if (wettest != null && expectedRain(wettest) >= 5) {
			items.add(new Item(String.format(Locale.ENGLISH, "Rain expected on %s: about %.0f mm (%d%% chance).",
					DAY.format(wettest.date()), nz(wettest.precipitationMm()),
					(wettest.precipitationProbability() != null) ? wettest.precipitationProbability() : 100),
					Tone.GOOD, "WEATHER", null));
		}
		else {
			items.add(new Item("No meaningful rain in the 7-day forecast: irrigation carries the crop this week.",
					Tone.WATCH, "WEATHER", null));
		}
		if (!s.weather().live()) {
			items.add(new Item("Weather service unreachable: figures use seasonal climatology until it reconnects.",
					Tone.WATCH, "WEATHER", null));
		}
		return new Section("weather", "Weather outlook", worst(items), items);
	}

	private static Section water(FarmSnapshot s) {
		WaterSummary week = s.week();
		WaterSummary month = s.month();
		List<Item> items = new ArrayList<>();
		if (week.requirementM3() > 0 || week.appliedM3() > 0) {
			String adequacy = (week.adequacyPct() == null) ? ""
					: String.format(Locale.ENGLISH, " (%.0f%% of crop need)", week.adequacyPct());
			Tone tone = (week.adequacyPct() == null) ? Tone.GOOD
					: (week.adequacyPct() < 70 || week.adequacyPct() > 130) ? Tone.WATCH : Tone.GOOD;
			items.add(new Item(String.format(Locale.ENGLISH, "Last 7 days: %.0f m³ applied%s.", week.appliedM3(),
					adequacy), tone, "WATER", null));
		}
		if (month.baselineM3() > 0) {
			items.add(new Item((month.savedM3() >= 0)
					? String.format(Locale.ENGLISH,
							"Last 30 days: %.0f m³ saved (%.0f%%) against a fixed calendar schedule.", month.savedM3(),
							(month.savedPct() != null) ? month.savedPct() : 0)
					: String.format(Locale.ENGLISH,
							"Last 30 days: %.0f m³ more than a fixed calendar schedule. Review irrigation depths.",
							-month.savedM3()),
					(month.savedM3() >= 0) ? Tone.GOOD : Tone.WATCH, "WATER", null));
		}
		if (week.energyCost() != null && week.energyCost() > 0) {
			items.add(new Item(String.format(Locale.ENGLISH, "Pumping energy this week: %.0f kWh (≈ %s %.2f).",
					week.energyKwh(), week.currency(), week.energyCost()), Tone.GOOD, "WATER", null));
		}
		week.allocations()
			.stream()
			.filter((a) -> a.usedPct() >= 75)
			.forEach((a) -> items.add(new Item(String.format(Locale.ENGLISH,
					"%s: %.0f%% of the annual allocation already drawn.", a.name(), a.usedPct()),
					(a.usedPct() >= 90) ? Tone.ACTION : Tone.WATCH, "ASSET", a.assetId())));
		return new Section("water", "Water use", worst(items), items);
	}

	private static Section infrastructure(FarmSnapshot s) {
		List<Item> items = new ArrayList<>();
		for (AssetHealthSnapshot a : s.assets()) {
			RiskLevel risk = a.health().risk();
			Factor top = a.health().factors().stream().filter((f) -> f.impact() < 0).findFirst().orElse(null);
			if (risk == RiskLevel.HIGH || risk == RiskLevel.CRITICAL) {
				items.add(new Item(String.format(Locale.ENGLISH, "%s (health %d/100): %s %s", a.asset().getName(),
						a.health().score(), (top != null) ? top.detail() : "", a.health().recommendedAction()).strip(),
						Tone.ACTION, "ASSET", a.asset().getId()));
			}
			else if (a.health().service() != null && a.health().service().daysRemaining() != null
					&& a.health().service().daysRemaining() >= 0 && a.health().service().daysRemaining() <= 14) {
				items.add(new Item(String.format(Locale.ENGLISH, "%s: service due %s.", a.asset().getName(),
						DAY.format(a.health().service().dueOn())), Tone.WATCH, "ASSET", a.asset().getId()));
			}
		}
		if (items.isEmpty() && !s.assets().isEmpty()) {
			items.add(new Item("All " + s.assets().size() + " registered assets are healthy; no service due in 14 days.",
					Tone.GOOD, "ASSET", null));
		}
		return new Section("infrastructure", "Water infrastructure", worst(items), items);
	}

	private static Section yieldProtection(FarmSnapshot s) {
		List<Item> items = new ArrayList<>();
		for (FieldWaterState f : s.fields()) {
			var season = f.result().season();
			if (season == null || season.daysAfterPlanting() < 7) {
				continue;
			}
			if (season.yieldReductionPct() >= 2) {
				items.add(new Item(String.format(Locale.ENGLISH,
						"%s: about %.0f%% of attainable yield lost to water stress so far (%d stress days); expected %.1f t/ha.",
						f.field().getName(), season.yieldReductionPct(), season.stressDays(),
						season.expectedYieldTHa()), (season.yieldReductionPct() >= 5) ? Tone.ACTION : Tone.WATCH,
						"FIELD", f.field().getId()));
			}
		}
		if (items.isEmpty() && s.fields().stream().anyMatch((f) -> f.result().season() != null)) {
			items.add(new Item("No yield lost to water stress this season on any planted field.", Tone.GOOD, "FIELD",
					null));
		}
		return new Section("yield", "Yield protection", worst(items), items);
	}

	private static Tone worst(List<Item> items) {
		return items.stream().map(Item::tone).max(Enum::compareTo).orElse(Tone.GOOD);
	}

	private static double expectedRain(WeatherDay day) {
		return nz(day.precipitationMm())
				* ((day.precipitationProbability() != null) ? day.precipitationProbability() / 100.0 : 1);
	}

	private static double nz(Double value) {
		return (value != null) ? value : 0;
	}

}
