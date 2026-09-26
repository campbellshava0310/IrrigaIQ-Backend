package com.irrigaiq.common.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HumanTextTest {

	@Test
	void durationsReadAsHoursAndMinutes() {
		assertThat(HumanText.duration(45)).isEqualTo("45 min");
		assertThat(HumanText.duration(120)).isEqualTo("2 h");
		assertThat(HumanText.duration(856)).isEqualTo("14 h 16 min");
	}

	@Test
	void daysArePluralised() {
		assertThat(HumanText.days(1)).isEqualTo("1 day");
		assertThat(HumanText.days(25)).isEqualTo("25 days");
	}

}
