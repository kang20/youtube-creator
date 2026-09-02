package kang20.ytcreator.subtitle.internal.handler.inbound;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;

/**
 * 완료 큐(Redis Stream) 소비자 — 메시지에는 작업 번호와 단계만 실린다. 산출물 위치는 서버가 이미 안다.
 * 처리 결과와 무관하게 ACK 한다: 통지는 힌트일 뿐이고, 놓친 완료는 조정 배치가 산출물로 회복한다.
 */
@Component
@ConditionalOnProperty(name = "ytcreator.subtitle.queue.enabled", havingValue = "true")
public class WorkCompletionConsumer implements StreamListener<String, MapRecord<String, String, String>> {

	private static final Logger log = LoggerFactory.getLogger(WorkCompletionConsumer.class);

	static final String FIELD_JOB_ID = "jobId";
	static final String FIELD_STAGE = "stage";

	private final SubtitleWorkerPort subtitleWorkerPort;
	private final StringRedisTemplate redisTemplate;
	private final String doneStream;
	private final String group;

	public WorkCompletionConsumer(SubtitleWorkerPort subtitleWorkerPort, StringRedisTemplate redisTemplate,
			@Value("${ytcreator.subtitle.queue.done-stream}") String doneStream,
			@Value("${ytcreator.subtitle.queue.group}") String group) {
		this.subtitleWorkerPort = subtitleWorkerPort;
		this.redisTemplate = redisTemplate;
		this.doneStream = doneStream;
		this.group = group;
	}

	@Override
	public void onMessage(MapRecord<String, String, String> message) {
		try {
			Map<String, String> fields = message.getValue();
			JobId jobId = new JobId(Long.parseLong(required(fields, FIELD_JOB_ID)));
			WorkStage stage = WorkStage.valueOf(required(fields, FIELD_STAGE));
			JobStatus status = switch (stage) {
				case SCRIPT -> subtitleWorkerPort.attachScript(jobId);
				case SUBTITLE -> subtitleWorkerPort.attachSubtitle(jobId);
			};
			log.info("[subtitle] 완료 통지 반영 — jobId={}, stage={}, status={}", jobId, stage, status);
		} catch (BusinessException rejected) {
			log.warn("[subtitle] 완료 통지 거절 — 조정 배치가 회복한다. id={}, code={}", message.getId(), rejected.getErrorCode());
		} catch (IllegalArgumentException malformed) {
			log.warn("[subtitle] 형식이 어긋난 완료 통지 — 버린다. id={}, fields={}", message.getId(), message.getValue());
		} catch (RuntimeException e) {
			log.error("[subtitle] 완료 통지 처리 실패 — 조정 배치가 회복한다. id={}", message.getId(), e);
		} finally {
			redisTemplate.opsForStream().acknowledge(doneStream, group, message.getId());
		}
	}

	private static String required(Map<String, String> fields, String name) {
		String value = fields.get(name);
		if (value == null) {
			throw new IllegalArgumentException(name + " 없음");
		}
		return value;
	}
}
