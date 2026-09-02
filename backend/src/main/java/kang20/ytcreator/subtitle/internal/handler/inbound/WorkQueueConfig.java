package kang20.ytcreator.subtitle.internal.handler.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;

/**
 * 완료 큐 소비자 그룹 조립 — 큐 설정이 켜졌는데 Redis 에 닿지 못하면 기동 실패다(fail-fast).
 * 조용히 뜨면 완료 통지를 아무도 읽지 않아 모든 작업이 30분 뒤 조정 배치에만 기댄다.
 */
@Configuration
@ConditionalOnProperty(name = "ytcreator.subtitle.queue.enabled", havingValue = "true")
class WorkQueueConfig {

	private static final Logger log = LoggerFactory.getLogger(WorkQueueConfig.class);

	@Bean
	StreamMessageListenerContainer<String, MapRecord<String, String, String>> workCompletionContainer(
			RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
			WorkCompletionConsumer consumer,
			@Value("${ytcreator.subtitle.queue.done-stream}") String doneStream,
			@Value("${ytcreator.subtitle.queue.group}") String group,
			@Value("${ytcreator.subtitle.queue.consumer}") String consumerName) {
		ensureGroup(redisTemplate, doneStream, group);

		var container = StreamMessageListenerContainer.create(connectionFactory);
		container.register(
			StreamReadRequest.builder(StreamOffset.create(doneStream, ReadOffset.lastConsumed()))
				.consumer(Consumer.from(group, consumerName))
				.autoAcknowledge(false)
				.cancelOnError(t -> false)   // 연결 장애로 구독이 끊기면 복구 뒤에도 아무도 읽지 않는다
				.errorHandler(t -> log.warn("[subtitle] 완료 큐 읽기 실패 — 다시 시도한다. cause={}", t.toString()))
				.build(),
			consumer);
		return container;
	}

	static void ensureGroup(StringRedisTemplate redisTemplate, String doneStream, String group) {
		try {
			redisTemplate.opsForStream().createGroup(doneStream, ReadOffset.from("0-0"), group);
		} catch (DataAccessException e) {
			String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
			if (!cause.contains("BUSYGROUP")) {
				throw e;
			}
		}
	}
}
