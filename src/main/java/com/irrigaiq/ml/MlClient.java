package com.irrigaiq.ml;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.irrigaiq.ml.MlApi.AssetFeatures;
import com.irrigaiq.ml.MlApi.EmbeddingRequest;
import com.irrigaiq.ml.MlApi.EmbeddingResponse;
import com.irrigaiq.ml.MlApi.EquipmentRequest;
import com.irrigaiq.ml.MlApi.EquipmentResponse;
import com.irrigaiq.ml.MlApi.EquipmentRisk;
import com.irrigaiq.ml.MlApi.FieldFeatures;
import com.irrigaiq.ml.MlApi.FlowPoint;
import com.irrigaiq.ml.MlApi.IrrigationPrediction;
import com.irrigaiq.ml.MlApi.IrrigationRequest;
import com.irrigaiq.ml.MlApi.IrrigationResponse;
import com.irrigaiq.ml.MlApi.WaterAnomalyRequest;
import com.irrigaiq.ml.MlApi.WaterAnomalyResult;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client of the ML service. Every call is optional by design: the platform's own rules and water
 * balance keep working when the service is down, so failures return empty instead of throwing.
 * After a failure the client stops calling for a while (a minimal circuit breaker), so a dead
 * service costs one timeout, not one per request.
 */
@Component
public class MlClient {

	private static final Logger log = LoggerFactory.getLogger(MlClient.class);

	private final RestClient client;

	private final MlProperties properties;

	private final Clock clock;

	private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.MIN);

	MlClient(RestClient.Builder builder, MlProperties properties, Clock clock) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.timeout());
		requestFactory.setReadTimeout(properties.timeout());
		RestClient.Builder configured = builder.clone().baseUrl(properties.baseUrl()).requestFactory(requestFactory);
		if (!properties.token().isBlank()) {
			configured.defaultHeader("X-Service-Token", properties.token());
		}
		this.client = configured.build();
		this.properties = properties;
		this.clock = clock;
	}

	/** Whether calls are currently attempted (enabled and not backing off after a failure). */
	public boolean available() {
		return properties.enabled() && clock.instant().isAfter(openUntil.get());
	}

	public Optional<List<IrrigationPrediction>> predictIrrigation(List<FieldFeatures> fields) {
		if (fields.isEmpty()) {
			return Optional.of(List.of());
		}
		return call("irrigation", () -> client.post()
			.uri("/v1/irrigation/predict")
			.body(new IrrigationRequest(fields))
			.retrieve()
			.body(IrrigationResponse.class)).map(IrrigationResponse::predictions);
	}

	public Optional<WaterAnomalyResult> waterAnomaly(String meterId, List<FlowPoint> history, List<FlowPoint> recent) {
		return call("water anomaly", () -> client.post()
			.uri("/v1/anomaly/water")
			.body(new WaterAnomalyRequest(meterId, history, recent))
			.retrieve()
			.body(WaterAnomalyResult.class));
	}

	public Optional<List<EquipmentRisk>> equipmentRisk(List<AssetFeatures> assets) {
		if (assets.isEmpty()) {
			return Optional.of(List.of());
		}
		return call("equipment", () -> client.post()
			.uri("/v1/equipment/risk")
			.body(new EquipmentRequest(assets))
			.retrieve()
			.body(EquipmentResponse.class)).map(EquipmentResponse::assets);
	}

	/** @param query whether the texts are search queries (bge models embed queries with an instruction) */
	public Optional<EmbeddingResponse> embed(List<String> texts, boolean query) {
		return call("embeddings", () -> client.post()
			.uri("/v1/embeddings")
			.body(new EmbeddingRequest(texts, query))
			.retrieve()
			.body(EmbeddingResponse.class));
	}

	/** Model cards: what each model does, how it was evaluated and its limitations. */
	public Optional<List<Map<String, Object>>> models() {
		return call("model cards", () -> client.get()
			.uri("/v1/models")
			.retrieve()
			.body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
			}));
	}

	private <T> Optional<T> call(String what, Supplier<T> request) {
		if (!available()) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(request.get());
		}
		catch (HttpClientErrorException ex) {
			// A 4xx is a bug on our side (bad input), not an outage: keep the circuit closed.
			log.warn("ML service rejected the {} request: {} {}", what, ex.getStatusCode(),
					ex.getResponseBodyAsString());
			return Optional.empty();
		}
		catch (RestClientException ex) {
			openUntil.set(clock.instant().plus(properties.retryAfterFailure()));
			log.warn("ML service unavailable for {} ({}); retrying in {}", what, ex.getMessage(),
					properties.retryAfterFailure());
			return Optional.empty();
		}
	}

}
