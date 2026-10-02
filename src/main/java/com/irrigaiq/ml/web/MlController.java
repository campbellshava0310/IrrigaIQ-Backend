package com.irrigaiq.ml.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.irrigaiq.ml.EquipmentRiskService;
import com.irrigaiq.ml.EquipmentRiskService.EquipmentRiskView;
import com.irrigaiq.ml.IrrigationModelService;
import com.irrigaiq.ml.IrrigationModelService.IrrigationModelView;
import com.irrigaiq.ml.MlClient;
import com.irrigaiq.ml.WaterAnomalyService;
import com.irrigaiq.ml.WaterAnomalyService.WaterAnomalyView;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine-learning outputs, kept apart from the rules and water balance they complement: each
 * response says whether the ML service answered ({@code available}), so clients can show the
 * rule-based result alone when it did not.
 */
@RestController
@Tag(name = "Machine learning")
public class MlController {

	private final IrrigationModelService irrigation;

	private final WaterAnomalyService water;

	private final EquipmentRiskService equipment;

	private final MlClient ml;

	public MlController(IrrigationModelService irrigation, WaterAnomalyService water, EquipmentRiskService equipment,
			MlClient ml) {
		this.irrigation = irrigation;
		this.water = water;
		this.equipment = equipment;
		this.ml = ml;
	}

	@GetMapping("/api/v1/farms/{farmId}/ml/irrigation")
	@Operation(summary = "Irrigation-need model per field, with its inputs, drivers and agreement with the water balance")
	public IrrigationModelView irrigation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID farmId) {
		return irrigation.assess(farmId, user);
	}

	@GetMapping("/api/v1/farms/{farmId}/ml/water-anomalies")
	@Operation(summary = "Anomaly check of every water meter: last 48 hours observed against expected")
	public WaterAnomalyView waterAnomalies(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID farmId) {
		return water.check(farmId, user);
	}

	@GetMapping("/api/v1/farms/{farmId}/ml/equipment-risk")
	@Operation(summary = "14-day failure risk of every pump, with the readings behind it")
	public EquipmentRiskView equipmentRisk(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID farmId) {
		return equipment.assess(farmId, user);
	}

	public record ModelCatalogue(boolean available, List<Map<String, Object>> models) {
	}

	@GetMapping("/api/v1/ml/models")
	@Operation(summary = "Model cards: what each model does, how it was evaluated, and its limitations")
	public ModelCatalogue models() {
		return ml.models().map((cards) -> new ModelCatalogue(true, cards)).orElse(new ModelCatalogue(false, List.of()));
	}

}
