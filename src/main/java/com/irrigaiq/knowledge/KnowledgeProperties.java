package com.irrigaiq.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param indexOnStartup load changed documents at startup and embed them when the ML service is up */
@ConfigurationProperties("irrigaiq.knowledge")
public record KnowledgeProperties(@DefaultValue("true") boolean indexOnStartup) {
}
