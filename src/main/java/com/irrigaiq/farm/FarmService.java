package com.irrigaiq.farm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;

import com.irrigaiq.audit.AuditAction;
import com.irrigaiq.audit.AuditEntry;
import com.irrigaiq.audit.AuditService;
import com.irrigaiq.common.error.ApiException;
import com.irrigaiq.farm.FarmViews.FarmView;
import com.irrigaiq.identity.User;
import com.irrigaiq.identity.UserRepository;
import com.irrigaiq.security.AuthenticatedUser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Farms, and the access rule every farm-scoped resource inherits: owners see their own farms,
 * administrators see all. Inaccessible farms are reported as not found so ids cannot be probed.
 */
@Service
public class FarmService {

	private final FarmRepository farms;

	private final FieldRepository fields;

	private final UserRepository users;

	private final AuditService audit;

	public FarmService(FarmRepository farms, FieldRepository fields, UserRepository users, AuditService audit) {
		this.farms = farms;
		this.fields = fields;
		this.users = users;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public Page<FarmView> list(AuthenticatedUser user, String query, Pageable pageable) {
		Specification<Farm> specification = (root, criteriaQuery, cb) -> {
			Predicate predicate = cb.conjunction();
			if (!user.isAdmin()) {
				predicate = cb.and(predicate, cb.equal(root.get("owner").get("id"), user.id()));
			}
			if (StringUtils.hasText(query)) {
				String pattern = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
				predicate = cb.and(predicate, cb.or(cb.like(cb.lower(root.get("name")), pattern),
						cb.like(cb.lower(root.get("location")), pattern)));
			}
			return predicate;
		};
		Page<Farm> page = farms.findAll(specification, pageable);
		Map<UUID, Long> fieldCounts = fieldCounts(page.map(Farm::getId).getContent());
		return page.map((farm) -> FarmView.from(farm, fieldCounts.getOrDefault(farm.getId(), 0L)));
	}

	@Transactional(readOnly = true)
	public FarmView get(UUID farmId, AuthenticatedUser user) {
		return view(requireAccessible(farmId, user));
	}

	@Transactional
	public FarmView create(AuthenticatedUser user, FarmDetails details) {
		User owner = users.findById(user.id()).orElseThrow(() -> ApiException.notFound("User"));
		Farm farm = farms.save(new Farm(owner, details));
		audit.record(AuditEntry.of(AuditAction.FARM_CREATED).target("FARM", farm.getId()).detail("name", farm.getName()));
		return FarmView.from(farm, 0);
	}

	@Transactional
	public FarmView update(UUID farmId, AuthenticatedUser user, FarmDetails details) {
		Farm farm = requireAccessible(farmId, user);
		farm.update(details);
		farms.flush(); // surface the new updatedAt/version in the response
		return view(farm);
	}

	@Transactional
	public void delete(UUID farmId, AuthenticatedUser user) {
		Farm farm = requireAccessible(farmId, user);
		farms.delete(farm); // fields, crops and telemetry cascade in the database
		audit.record(AuditEntry.of(AuditAction.FARM_DELETED).target("FARM", farmId).detail("name", farm.getName()));
	}

	/** Loads a farm the user may act on, or fails with 404. */
	@Transactional(readOnly = true)
	public Farm requireAccessible(UUID farmId, AuthenticatedUser user) {
		Farm farm = farms.findWithOwnerById(farmId).orElseThrow(() -> ApiException.notFound("Farm"));
		if (!user.isAdmin() && !farm.isOwnedBy(user.id())) {
			throw ApiException.notFound("Farm");
		}
		return farm;
	}

	private FarmView view(Farm farm) {
		return FarmView.from(farm, fieldCounts(List.of(farm.getId())).getOrDefault(farm.getId(), 0L));
	}

	private Map<UUID, Long> fieldCounts(List<UUID> farmIds) {
		if (farmIds.isEmpty()) {
			return Map.of();
		}
		return fields.countByFarmIds(farmIds)
			.stream()
			.collect(Collectors.toMap(FieldRepository.FarmFieldCount::getFarmId,
					FieldRepository.FarmFieldCount::getTotal));
	}

}
