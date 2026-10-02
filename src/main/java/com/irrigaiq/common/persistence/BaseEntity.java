package com.irrigaiq.common.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.Hibernate;
import org.hibernate.annotations.UuidGenerator;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Common identity, audit timestamps and optimistic locking for aggregate roots. Ids are UUIDv7:
 * globally unique, safe to expose, and time-ordered so B-tree inserts stay append-mostly.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	private UUID id;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	public UUID getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public long getVersion() {
		return version;
	}

	@Override
	public final boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		return id != null && id.equals(((BaseEntity) other).getId());
	}

	@Override
	public final int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
