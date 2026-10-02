package com.irrigaiq.farm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Normalises decimals to their column scale so API responses match what the database stores. */
final class Decimals {

	private Decimals() {
	}

	static BigDecimal scale(BigDecimal value, int scale) {
		return (value != null) ? value.setScale(scale, RoundingMode.HALF_UP) : null;
	}

}
