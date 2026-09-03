package kang20.ytcreator.subtitle.internal.service.support;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;

@Support
@ConditionalOnProperty(name = "ytcreator.subtitle.queue.enabled", havingValue = "true")
public class RedisStreamWorkDispatcher implements WorkDispatcher {

	static final String FIELD_JOB_ID = "jobId";
	static final String FIELD_STAGE = "stage";
	static final String FIELD_INPUT_KEY = "inputKey";
	static final String FIELD_OUTPUT_KEY = "outputKey";

	private final StringRedisTemplate redisTemplate;
	private final String workStream;

	public RedisStreamWorkDispatcher(StringRedisTemplate redisTemplate,
			@Value("${ytcreator.subtitle.queue.work-stream}") String workStream) {
		this.redisTemplate = redisTemplate;
		this.workStream = workStream;
	}

	@Override
	public void dispatch(JobId jobId, WorkStage stage) {
		Map<String, String> fields = Map.of(
			FIELD_JOB_ID, String.valueOf(jobId.longValue()),
			FIELD_STAGE, stage.name(),
			FIELD_INPUT_KEY, stage.input(jobId).value(),
			FIELD_OUTPUT_KEY, stage.output(jobId).value());
		redisTemplate.<String, String>opsForStream()
			.add(StreamRecords.newRecord().in(workStream).ofMap(fields));
	}
}
