package kang20.ytcreator.subtitle.internal.handler.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.stream.Stream;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 완료 큐 소비자 — 메시지의 작업 번호·단계만 읽어 완료 통지 포트로 넘기고, 결과와 무관하게 ACK 한다
 * (subtitle-v3 완료 큐). 놓친 완료는 조정 배치가 산출물로 회복하므로 여기서 재시도하지 않는다.
 */
class WorkCompletionConsumerTest {

	private static final String DONE_STREAM = "test:subtitle:done";
	private static final String GROUP = "test-server";
	private static final RecordId ID = RecordId.of("1700000000000-0");

	private SubtitleWorkerPort port;
	private StreamOperations<String, String, String> ops;
	private WorkCompletionConsumer consumer;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void 준비() {
		port = mock(SubtitleWorkerPort.class);
		ops = mock(StreamOperations.class);
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		doReturn(ops).when(template).opsForStream();
		consumer = new WorkCompletionConsumer(port, template, DONE_STREAM, GROUP);
	}

	private static MapRecord<String, String, String> message(Map<String, String> fields) {
		return StreamRecords.newRecord().in(DONE_STREAM).ofMap(fields).withId(ID);
	}

	@Test
	@DisplayName("대본 완료 통지는 attachScript 로 넘어가고 ACK 된다")
	void 대본_완료_통지는_attachScript_로_넘어가고_ACK_된다() {
		when(port.attachScript(new JobId(7L))).thenReturn(JobStatus.COMPLETED_SCRIPT);

		consumer.onMessage(message(Map.of("jobId", "7", "stage", "SCRIPT")));

		verify(port).attachScript(new JobId(7L));
		verify(ops).acknowledge(DONE_STREAM, GROUP, ID);
	}

	@Test
	@DisplayName("자막 완료 통지는 attachSubtitle 로 넘어가고 ACK 된다")
	void 자막_완료_통지는_attachSubtitle_로_넘어가고_ACK_된다() {
		when(port.attachSubtitle(new JobId(7L))).thenReturn(JobStatus.COMPLETED_SUBTITLE);

		consumer.onMessage(message(Map.of("jobId", "7", "stage", "SUBTITLE")));

		verify(port).attachSubtitle(new JobId(7L));
		verify(ops).acknowledge(DONE_STREAM, GROUP, ID);
	}

	/** 통지는 힌트다 — 거절돼도 큐에 남기지 않는다. 산출물이 정말 있으면 조정 배치가 회복한다 */
	@Test
	@DisplayName("거절된 통지(상태 불일치·산출물 없음)도 ACK 되고 예외가 새지 않는다")
	void 거절된_통지도_ACK_되고_예외가_새지_않는다() {
		when(port.attachScript(any())).thenThrow(new BusinessException(ErrorCode.SUBTITLE_002));

		assertThatCode(() -> consumer.onMessage(message(Map.of("jobId", "7", "stage", "SCRIPT"))))
			.doesNotThrowAnyException();

		verify(ops).acknowledge(DONE_STREAM, GROUP, ID);
	}

	@Test
	@DisplayName("처리 실패(저장소·DB 장애)도 ACK 되고 예외가 새지 않는다 — 회복은 조정 배치 몫이다")
	void 처리_실패도_ACK_되고_예외가_새지_않는다() {
		when(port.attachSubtitle(any())).thenThrow(new IllegalStateException("db unavailable"));

		assertThatCode(() -> consumer.onMessage(message(Map.of("jobId", "7", "stage", "SUBTITLE"))))
			.doesNotThrowAnyException();

		verify(ops).acknowledge(DONE_STREAM, GROUP, ID);
	}

	static Stream<Map<String, String>> malformed() {
		return Stream.of(
			Map.of("stage", "SCRIPT"),
			Map.of("jobId", "7"),
			Map.of("jobId", "seven", "stage", "SCRIPT"),
			Map.of("jobId", "7", "stage", "ENCODE"));
	}

	@ParameterizedTest
	@MethodSource("malformed")
	@DisplayName("형식이 어긋난 통지는 포트를 부르지 않고 버린다(ACK)")
	void 형식이_어긋난_통지는_포트를_부르지_않고_버린다(Map<String, String> fields) {
		assertThatCode(() -> consumer.onMessage(message(fields))).doesNotThrowAnyException();

		verifyNoInteractions(port);
		verify(ops).acknowledge(DONE_STREAM, GROUP, ID);
	}
}
