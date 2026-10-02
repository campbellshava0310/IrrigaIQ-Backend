package com.irrigaiq.agronomy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SoilMoistureScaleTest {

	@Test
	void readingsMapLinearlyBetweenFieldCapacityAndWiltingPoint() {
		assertThat(SoilMoistureScale.depletionPct(40, null, null)).isZero();
		assertThat(SoilMoistureScale.depletionPct(15, null, null)).isEqualTo(100);
		assertThat(SoilMoistureScale.depletionPct(23, null, null)).isCloseTo(68, within(1e-9));
		assertThat(SoilMoistureScale.depletionPct(30, 36.0, 16.0)).isCloseTo(30, within(1e-9));
	}

	@Test
	void wetterThanFieldCapacityOrDrierThanWiltingIsClamped() {
		assertThat(SoilMoistureScale.depletionPct(55, null, null)).isZero();
		assertThat(SoilMoistureScale.depletionPct(5, null, null)).isEqualTo(100);
	}

	@Test
	void defaultScaleRoundTrips() {
		for (double depletion : new double[] { 0, 25, 60, 100 }) {
			double reading = SoilMoistureScale.defaultScaleReading(depletion);
			assertThat(SoilMoistureScale.depletionPct(reading, null, null)).isCloseTo(depletion, within(1e-9));
		}
		// The model's learnt dry threshold (~25 %) sits at 60 % depletion.
		assertThat(SoilMoistureScale.defaultScaleReading(60)).isCloseTo(25, within(1e-9));
	}

	@Test
	void rejectsInvertedCalibration() {
		assertThatThrownBy(() -> SoilMoistureScale.depletionPct(20, 15.0, 30.0))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
