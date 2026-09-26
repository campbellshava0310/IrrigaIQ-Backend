package com.irrigaiq.farm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.farm.FarmViews.CropView;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CropService {

	private final CropRepository crops;

	private final FieldService fields;

	public CropService(CropRepository crops, FieldService fields) {
		this.crops = crops;
		this.fields = fields;
	}

	@Transactional(readOnly = true)
	public List<CropView> list(UUID farmId, UUID fieldId, AuthenticatedUser user) {
		fields.requireAccessible(farmId, fieldId, user);
		return crops.findByFieldIdOrderByPlantingDateDesc(fieldId).stream().map(CropView::from).toList();
	}

	@Transactional
	public CropView create(UUID farmId, UUID fieldId, AuthenticatedUser user, CropDetails details) {
		Field field = fields.requireAccessible(farmId, fieldId, user);
		Crop crop = crops.save(new Crop(field, details.cropType(), details.variety(), details.plantingDate(),
				details.growthStage(), details.expectedYieldTHa()));
		return CropView.from(crop);
	}

	@Transactional
	public CropView update(UUID farmId, UUID fieldId, UUID cropId, AuthenticatedUser user, CropDetails details) {
		Crop crop = require(farmId, fieldId, cropId, user);
		crop.update(details.cropType(), details.variety(), details.plantingDate(), details.growthStage(),
				details.expectedYieldTHa());
		crops.flush();
		return CropView.from(crop);
	}

	@Transactional
	public void delete(UUID farmId, UUID fieldId, UUID cropId, AuthenticatedUser user) {
		crops.delete(require(farmId, fieldId, cropId, user));
	}

	private Crop require(UUID farmId, UUID fieldId, UUID cropId, AuthenticatedUser user) {
		fields.requireAccessible(farmId, fieldId, user);
		return crops.findByIdAndFieldId(cropId, fieldId).orElseThrow(() -> ApiException.notFound("Crop"));
	}

	public record CropDetails(CropType cropType, String variety, LocalDate plantingDate, GrowthStage growthStage,
			BigDecimal expectedYieldTHa) {
	}

}
