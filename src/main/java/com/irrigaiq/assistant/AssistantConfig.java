package com.irrigaiq.assistant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class AssistantConfig {

	/**
	 * Answers run off the request thread while the response streams. No queue: when every worker is
	 * busy a new question is refused at once (503) instead of waiting behind long answers.
	 */
	@Bean
	ThreadPoolTaskExecutor assistantExecutor() {
		ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
		pool.setThreadNamePrefix("assistant-");
		pool.setCorePoolSize(4);
		pool.setMaxPoolSize(16);
		pool.setQueueCapacity(0);
		pool.setWaitForTasksToCompleteOnShutdown(true);
		pool.setAwaitTerminationSeconds(30);
		return pool;
	}

}
