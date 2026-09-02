package kang20.ytcreator.subtitle.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 작업 큐 XADD — 메시지 필드가 워커와의 계약이다(subtitle-v3 처리 의뢰). 위치 규칙은 서버가 다 적어 준다.
 */
class RedisStreamWorkDispatcherTest {

	private static final String WORK_STREAM = "test:subtitle:work";
	private static final JobId JOB = new JobId(7L);

	private StreamOperations<String, String, String> ops;
	private RedisStreamWorkDispatcher dispatcher;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void 준비() {
		ops = mock(StreamOperations.class);
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		doReturn(ops).when(template).opsForStream();
		dispatcher = new RedisStreamWorkDispatcher(template, WORK_STREAM);
	}

	@Test
	@DisplayName("대본 의뢰는 작업 번호·단계·원본 위치·대본 위치를 작업 큐에 싣는다")
	void 대본_의뢰_메시지() {
		dispatcher.dispatch(JOB, WorkStage.SCRIPT);

		MapRecord<String, String, String> record = captured();
		assertThat(record.getStream()).isEqualTo(WORK_STREAM);
		assertThat(record.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
			"jobId", "7", "stage", "SCRIPT", "inputKey", "jobs/7/source", "outputKey", "jobs/7/script"));
	}

	@Test
	@DisplayName("자막 의뢰는 확정 대본 위치를 입력으로, 자막 위치를 산출물로 싣는다")
	void 자막_의뢰_메시지() {
		dispatcher.dispatch(JOB, WorkStage.SUBTITLE);

		assertThat(captured().getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
			"jobId", "7", "stage", "SUBTITLE", "inputKey", "jobs/7/script", "outputKey", "jobs/7/subtitle"));
	}

	@Test
	@DisplayName("큐 장애는 삼키지 않는다 — 예외가 올라가야 아웃박스가 미완료로 남는다")
	void 큐_장애는_삼키지_않는다() {
		when(ops.add(any(MapRecord.class))).thenThrow(new RedisConnectionFailureException("down"));

		assertThatThrownBy(() -> dispatcher.dispatch(JOB, WorkStage.SCRIPT))
			.isInstanceOf(RedisConnectionFailureException.class);
	}

	@SuppressWarnings("unchecked")
	private MapRecord<String, String, String> captured() {
		ArgumentCaptor<MapRecord<String, String, String>> captor = ArgumentCaptor.forClass(MapRecord.class);
		verify(ops).add(captor.capture());
		return captor.getValue();
	}
}
