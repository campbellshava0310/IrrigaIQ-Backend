package com.irrigaiq.common.text;

/** Small helpers for the plain-language text in recommendations, alerts and briefings. */
public final class HumanText {

	private HumanText() {
	}

	/** 45 -> "45 min", 120 -> "2 h", 856 -> "14 h 16 min". */
	public static String duration(long minutes) {
		long hours = minutes / 60;
		long rest = minutes % 60;
		if (hours == 0) {
			return rest + " min";
		}
		return (rest == 0) ? hours + " h" : hours + " h " + rest + " min";
	}

	/** 1 -> "1 day", 3 -> "3 days". */
	public static String days(long count) {
		return count + ((Math.abs(count) == 1) ? " day" : " days");
	}

}
