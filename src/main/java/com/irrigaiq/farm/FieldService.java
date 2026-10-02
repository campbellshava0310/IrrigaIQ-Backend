package com.irrigaiq.farm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.common.error.ErrorCode;
import com.irrigaiq.farm.FarmViews.FieldView;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldService {

	private final FieldRepository fields;

	private final FarmService farms;

	public FieldService(FieldRepository fields, FarmService farms) {
		this.fields = fields;
		this.farms = farms;
	}

	@Transactional(readOnly = true)
	public List<FieldView> list(UUID farmId, AuthenticatedUser user) {
		farms.requireAccessible(farmId, user);
		return fields.findByFarmIdOrderByNameAsc(farmId).stream().map(FieldView::from).toList();
	}

	@Transactional(readOnly = true)
	public FieldView get(UUID farmId, UUID fieldId, AuthenticatedUser user) {
		return FieldView.from(requireAccessible(farmId, fieldId, user));
	}

	@Transactional
	public FieldView create(UUID farmId, AuthenticatedUser user, FieldDetails details) {
		Farm farm = farms.requireAccessible(farmId, user);
		if (fields.existsByFarmIdAndNameIgnoreCase(farmId, details.name().strip())) {
			throw duplicateName();
		}
		Field field = fields.save(new Field(farm, details.name(), details.areaHa(), details.soilType(),
				details.latitude(), details.longitude()));
		return FieldView.from(field);
	}

	@Transactional
	public FieldView update(UUID farmId, UUID fieldId, AuthenticatedUser user, FieldDetails details) {
		Field field = requireAccessible(farmId, fieldId, user);
		if (fields.existsByFarmIdAndNameIgnoreCaseAndIdNot(farmId, details.name().strip(), fieldId)) {
			throw duplicateName();
		}
		field.update(details.name(), details.areaHa(), details.soilType(), details.latitude(), details.longitude());
		fields.flush();
		return FieldView.from(field);
	}

	@Transactional
	public void delete(UUID farmId, UUID fieldId, AuthenticatedUser user) {
		fields.delete(requireAccessible(farmId, fieldId, user));
	}

	/** Loads a field within a farm the user may act on, or fails with 404. */
	@Transactional(readOnly = true)
	public Field requireAccessible(UUID farmId, UUID fieldId, AuthenticatedUser user) {
		farms.requireAccessible(farmId, user);
		return fields.findByIdAndFarmId(fieldId, farmId).orElseThrow(() -> ApiException.notFound("Field"));
	}

	// ---- Module API (callers have already authorised the farm) ----

	@Transactional(readOnly = true)
	public List<Field> fieldsOf(UUID farmId) {
		return fields.findByFarmIdOrderByNameAsc(farmId);
	}

	@Transactional(readOnly = true)
	public Optional<Field> findInFarm(UUID farmId, UUID fieldId) {
		return fields.findByIdAndFarmId(fieldId, farmId);
	}

	private static ApiException duplicateName() {
		return ApiException.conflict(ErrorCode.CONFLICT, "This farm already has a field with that name");
	}

	public record FieldDetails(String name, BigDecimal areaHa, SoilType soilType, BigDecimal latitude,
			BigDecimal longitude) {
	}

}
