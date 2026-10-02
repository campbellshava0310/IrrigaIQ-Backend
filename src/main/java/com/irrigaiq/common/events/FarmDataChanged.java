package com.irrigaiq.common.events;

import java.util.UUID;

/**
 * Published after anything that can change a farm's irrigation or infrastructure picture
 * (irrigation logged, inspection recorded, work order closed, ...). Listeners run after commit.
 */
public record FarmDataChanged(UUID farmId) {
}
