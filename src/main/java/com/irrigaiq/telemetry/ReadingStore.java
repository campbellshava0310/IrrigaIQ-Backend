package com.irrigaiq.telemetry;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Time-series access to {@code sensor_readings}: idempotent batch inserts (a device retrying a batch
 * never duplicates data) and aggregates computed in the database.
 */
@Repository
public class ReadingStore {

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	ReadingStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Hourly aggregate. {@code litresPerHour} is the mean flow rate over the hour, in litres per hour. */
	public record Hourly(Instant hour, int samples, Double soilMoisturePct, Double temperatureC, Double litresPerHour,
			Double pressureBar, Double powerKw, Double vibrationMmS, Double motorTempC) {
	}

	/** Means over a window, for pump condition. Energy per m³ only counts samples where the pump ran. */
	public record Window(int samples, Double kwhPerM3, Double flowLpm, Double pressureBar, Double vibrationMmS,
			Double motorTempC) {
	}

	/** @return the number of readings stored (readings already stored for the same instant are skipped) */
	int insert(UUID sensorId, ReadingSource source, List<Reading> readings, Instant receivedAt) {
		int[][] counts = jdbcTemplate.batchUpdate("""
				insert into sensor_readings (sensor_id, recorded_at, received_at, source, soil_moisture, temperature,
				                             humidity, rainfall, flow_rate, volume_litres, pressure, power, vibration,
				                             motor_temperature)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (sensor_id, recorded_at) do nothing
				""", readings, 500, (ps, r) -> {
			ps.setObject(1, sensorId);
			ps.setTimestamp(2, Timestamp.from(r.recordedAt()));
			ps.setTimestamp(3, Timestamp.from(receivedAt));
			ps.setString(4, source.name());
			Double[] values = { r.soilMoisturePct(), r.temperatureC(), r.humidityPct(), r.rainfallMm(), r.flowLpm(),
					r.volumeLitres(), r.pressureBar(), r.powerKw(), r.vibrationMmS(), r.motorTempC() };
			for (int i = 0; i < values.length; i++) {
				if (values[i] != null) {
					ps.setDouble(5 + i, values[i]);
				}
				else {
					ps.setNull(5 + i, Types.NUMERIC);
				}
			}
		});
		return Arrays.stream(counts).flatMapToInt(Arrays::stream).map((n) -> Math.max(0, n)).sum();
	}

	public Optional<Reading> latest(UUID sensorId) {
		return jdbc.sql("""
				select recorded_at, soil_moisture, temperature, humidity, rainfall, flow_rate, volume_litres, pressure,
				       power, vibration, motor_temperature
				from sensor_readings where sensor_id = ? order by recorded_at desc limit 1
				""").param(sensorId).query((rs, row) -> reading(rs)).optional();
	}

	/**
	 * Hourly means in UTC hours. Pulse meters report litres since their previous reading, which is
	 * turned into a rate over the gap (gaps over two hours are treated as the device being offline).
	 */
	public List<Hourly> hourly(UUID sensorId, Instant from, Instant to) {
		return jdbc.sql("""
				with r as (
				    select recorded_at, soil_moisture, temperature, flow_rate, volume_litres, pressure, power, vibration,
				           motor_temperature,
				           extract(epoch from recorded_at - lag(recorded_at) over (order by recorded_at)) / 60.0 as gap_min
				    from sensor_readings
				    where sensor_id = :sensor and recorded_at >= :lookback and recorded_at < :to
				)
				select date_trunc('hour', recorded_at, 'UTC') as hour, count(*) as samples,
				       avg(soil_moisture) as soil_moisture, avg(temperature) as temperature,
				       avg(coalesce(flow_rate, case when gap_min > 0 and gap_min <= 120 then volume_litres / gap_min end))
				           * 60 as litres_per_hour,
				       avg(pressure) as pressure, avg(power) as power, avg(vibration) as vibration,
				       avg(motor_temperature) as motor_temperature
				from r where recorded_at >= :from
				group by 1 order by 1
				""")
			.param("sensor", sensorId)
			.param("lookback", Timestamp.from(from.minusSeconds(7200)))
			.param("from", Timestamp.from(from))
			.param("to", Timestamp.from(to))
			.query((rs, row) -> new Hourly(rs.getTimestamp("hour").toInstant(), rs.getInt("samples"),
					decimal(rs, "soil_moisture"), decimal(rs, "temperature"), decimal(rs, "litres_per_hour"),
					decimal(rs, "pressure"), decimal(rs, "power"), decimal(rs, "vibration"),
					decimal(rs, "motor_temperature")))
			.list();
	}

	public Window window(UUID sensorId, Instant from, Instant to) {
		return jdbc.sql("""
				select count(*) as samples,
				       avg(power / (flow_rate * 0.06)) filter (where flow_rate > 0 and power > 0) as kwh_per_m3,
				       avg(flow_rate) filter (where flow_rate > 0) as flow,
				       avg(pressure) filter (where flow_rate is null or flow_rate > 0) as pressure,
				       avg(vibration) as vibration, avg(motor_temperature) as motor_temperature
				from sensor_readings where sensor_id = ? and recorded_at >= ? and recorded_at < ?
				""")
			.params(sensorId, Timestamp.from(from), Timestamp.from(to))
			.query((rs, row) -> new Window(rs.getInt("samples"), decimal(rs, "kwh_per_m3"), decimal(rs, "flow"),
					decimal(rs, "pressure"), decimal(rs, "vibration"), decimal(rs, "motor_temperature")))
			.single();
	}

	private static Reading reading(ResultSet rs) throws SQLException {
		return new Reading(rs.getTimestamp("recorded_at").toInstant(), decimal(rs, "soil_moisture"),
				decimal(rs, "temperature"), decimal(rs, "humidity"), decimal(rs, "rainfall"), decimal(rs, "flow_rate"),
				decimal(rs, "volume_litres"), decimal(rs, "pressure"), decimal(rs, "power"), decimal(rs, "vibration"),
				decimal(rs, "motor_temperature"));
	}

	private static Double decimal(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return (value != null) ? value.doubleValue() : null;
	}

}
