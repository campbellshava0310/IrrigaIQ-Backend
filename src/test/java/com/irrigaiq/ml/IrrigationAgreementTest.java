package com.irrigaiq.ml;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.irrigaiq.agronomy.WaterBalanceEngine.WaterStatus;
import com.irrigaiq.ml.MlApi.IrrigationPrediction;

import static org.assertj.core.api.Assertions.assertThat;

class IrrigationAgreementTest {

	@ParameterizedTest
	@CsvSource({ "High, STRESSED, true", "High, IRRIGATE_NOW, true", "High, IRRIGATE_SOON, true",
			"High, ADEQUATE, false", "Medium, IRRIGATE_NOW, true", "Medium, ADEQUATE, true", "Medium, STRESSED, false",
			"Low, ADEQUATE, true", "Low, IRRIGATE_SOON, true", "Low, IRRIGATE_NOW, false" })
	void needLevelsAreComparedWithTheWaterBalance(String need, WaterStatus status, boolean consistent) {
		IrrigationPrediction prediction = new IrrigationPrediction("f", need, !"Low".equals(need), Map.of(), 0.9, 0.9,
				0.5, List.of(), List.of(), "test");
		IrrigationModelService.Agreement agreement = IrrigationModelService.agreement(prediction, status);
		assertThat(agreement.consistent()).isEqualTo(consistent);
		assertThat(agreement.note()).isNotBlank();
	}

}
