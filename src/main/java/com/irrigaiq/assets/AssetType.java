package com.irrigaiq.assets;

/** Water-infrastructure asset types, grouped by the role they play in the irrigation network. */
public enum AssetType {

	BOREHOLE(Category.WATER_SOURCE), RESERVOIR(Category.WATER_SOURCE), RIVER_INTAKE(Category.WATER_SOURCE),
	STORAGE_TANK(Category.WATER_SOURCE),

	PUMP(Category.PUMPING), SOLAR_ARRAY(Category.POWER), GENERATOR(Category.POWER),

	FILTER(Category.TREATMENT), FERTIGATION_UNIT(Category.TREATMENT),

	MAINLINE(Category.DISTRIBUTION), VALVE(Category.DISTRIBUTION), FLOW_METER(Category.DISTRIBUTION),

	DRIP_SYSTEM(Category.APPLICATION), SPRINKLER_SYSTEM(Category.APPLICATION), CENTER_PIVOT(Category.APPLICATION),

	CONTROLLER(Category.CONTROL);

	public enum Category {

		WATER_SOURCE, PUMPING, POWER, TREATMENT, DISTRIBUTION, APPLICATION, CONTROL

	}

	private final Category category;

	AssetType(Category category) {
		this.category = category;
	}

	public Category category() {
		return category;
	}

}
