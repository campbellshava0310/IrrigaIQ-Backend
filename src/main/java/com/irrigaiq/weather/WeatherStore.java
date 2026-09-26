package com.irrigaiq.weather;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-farm daily weather cache. Live data always replaces climatology, never the reverse. */
@Repository
class WeatherStore {

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	private final TransactionTemplate ownTransaction;

	WeatherStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate, PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.ownTransaction = new TransactionTemplate(transactions);
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	record Cached(WeatherDay day, Instant fetchedAt) {
	}

	List<Cached> find(UUID farmId, LocalDate from, LocalDate to) {
		return jdbc.sql("""
				select day, et0_mm, precipitation_mm, precipitation_probability, temp_max_c, temp_min_c, source, fetched_at
				from weather_daily where farm_id = ? and day between ? and ? order by day
				""")
			.params(farmId, from, to)
			.query((rs, row) -> new Cached(new WeatherDay(rs.getObject("day", LocalDate.class),
					decimal(rs, "et0_mm"), decimal(rs, "precipitation_mm"),
					rs.getObject("precipitation_probability", Integer.class), decimal(rs, "temp_max_c"),
					decimal(rs, "temp_min_c"), WeatherSource.valueOf(rs.getString("source")), false),
					rs.getTimestamp("fetched_at").toInstant()))
			.list();
	}

	/** numeric columns come back as BigDecimal; the driver cannot convert them to Double directly. */
	private static Double decimal(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return (value != null) ? value.doubleValue() : null;
	}

	/**
	 * Caches in a transaction of its own, since most callers are read-only request transactions.
	 * Skipped (returns false) while the caller is itself writing: a separate transaction cannot see a
	 * farm the caller has not committed yet (e.g. one being set up), and writing inside the caller's
	 * transaction would let a failed insert abort it. The next read caches the data instead.
	 *
	 * @return whether the days were cached
	 */
	boolean upsert(UUID farmId, List<WeatherDay> days, Instant fetchedAt) {
		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
			return false;
		}
		ownTransaction.executeWithoutResult((status) -> write(farmId, days, fetchedAt));
		return true;
	}

	private void write(UUID farmId, List<WeatherDay> days, Instant fetchedAt) {
		jdbcTemplate.batchUpdate("""
				insert into weather_daily (farm_id, day, et0_mm, precipitation_mm, precipitation_probability,
				                           temp_max_c, temp_min_c, source, fetched_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (farm_id, day) do update set
				    et0_mm = excluded.et0_mm, precipitation_mm = excluded.precipitation_mm,
				    precipitation_probability = excluded.precipitation_probability, temp_max_c = excluded.temp_max_c,
				    temp_min_c = excluded.temp_min_c, source = excluded.source, fetched_at = excluded.fetched_at
				where weather_daily.source = 'CLIMATOLOGY' or excluded.source = 'OPEN_METEO'
				""", days, 200, (ps, day) -> {
			ps.setObject(1, farmId);
			ps.setDate(2, Date.valueOf(day.date()));
			setNullable(ps, 3, day.et0Mm());
			setNullable(ps, 4, day.precipitationMm());
			if (day.precipitationProbability() != null) {
				ps.setInt(5, day.precipitationProbability());
			}
			else {
				ps.setNull(5, Types.SMALLINT);
			}
			setNullable(ps, 6, day.tempMaxC());
			setNullable(ps, 7, day.tempMinC());
			ps.setString(8, day.source().name());
			ps.setTimestamp(9, Timestamp.from(fetchedAt));
		});
	}

	private static void setNullable(java.sql.PreparedStatement ps, int index, Double value)
			throws java.sql.SQLException {
		if (value != null) {
			ps.setDouble(index, value);
		}
		else {
			ps.setNull(index, Types.NUMERIC);
		}
	}

}
