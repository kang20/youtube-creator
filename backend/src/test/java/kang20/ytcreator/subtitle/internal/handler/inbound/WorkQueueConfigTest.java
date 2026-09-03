package kang20.ytcreator.subtitle.internal.handler.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class WorkQueueConfigTest {

	private static final String DONE_STREAM = "test:subtitle:done";
	private static final String GROUP = "test-server";

	private StreamOperations<String, String, String> ops;
	private StringRedisTemplate template;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void 준비() {
		ops = mock(StreamOperations.class);
		template = mock(StringRedisTemplate.class);
		doReturn(ops).when(template).opsForStream();
	}

	@Test
	@DisplayName("그룹이 없으면 스트림 처음부터 읽는 소비자 그룹을 만든다")
	void 그룹이_없으면_소비자_그룹을_만든다() {
		when(ops.createGroup(anyString(), any(ReadOffset.class), anyString())).thenReturn("OK");

		WorkQueueConfig.ensureGroup(template, DONE_STREAM, GROUP);

		verify(ops).createGroup(DONE_STREAM, ReadOffset.from("0-0"), GROUP);
	}

	@Test
	@DisplayName("이미 있는 그룹(BUSYGROUP)은 오류가 아니다 — 재기동이 막히면 안 된다")
	void 이미_있는_그룹은_오류가_아니다() {
		when(ops.createGroup(anyString(), any(ReadOffset.class), anyString()))
			.thenThrow(new RedisSystemException("Error in execution",
				new IllegalStateException("BUSYGROUP Consumer Group name already exists")));

		assertThatCode(() -> WorkQueueConfig.ensureGroup(template, DONE_STREAM, GROUP))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Redis 에 닿지 못하면 삼키지 않는다 — 조용히 뜨면 완료 통지를 아무도 읽지 않는다")
	void 닿지_못하면_삼키지_않는다() {
		when(ops.createGroup(anyString(), any(ReadOffset.class), anyString()))
			.thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

		assertThatThrownBy(() -> WorkQueueConfig.ensureGroup(template, DONE_STREAM, GROUP))
			.isInstanceOf(RedisConnectionFailureException.class);
		verify(ops).createGroup(eq(DONE_STREAM), any(ReadOffset.class), eq(GROUP));
	}
}
