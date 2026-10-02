package com.irrigaiq.audit;

/** Audited actions. Stored by name: append new values, never rename existing ones. */
public enum AuditAction {

	// Authentication
	LOGIN, LOGOUT, ACCOUNT_LOCKED, REFRESH_TOKEN_REUSE,

	// Self-service account management
	USER_REGISTERED, PROFILE_UPDATED, PASSWORD_CHANGED, SESSION_REVOKED,

	// Administration
	USER_CREATED, USER_UPDATED, USER_UNLOCKED, USER_SESSIONS_REVOKED,

	// Farm management
	FARM_CREATED, FARM_DELETED,

	// Infrastructure & maintenance
	ASSET_CREATED, ASSET_DELETED, ASSET_STATUS_CHANGED, WORK_ORDER_COMPLETED,

	// Platform
	DEMO_FARM_CREATED,

	// Devices & AI
	DEVICE_REGISTERED, DEVICE_KEY_ROTATED, DEVICE_DELETED, ASSISTANT_CHAT

}
