package kang20.ytcreator.subtitle.internal.handler.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import kang20.ytcreator.auth.CurrentUser;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.dto.JobDetail;
import kang20.ytcreator.subtitle.internal.entity.dto.JobList;
import kang20.ytcreator.subtitle.internal.entity.dto.JobOpened;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SubtitleJobController {
	private final SubtitleJobPort subtitleJobPort;

	@PostMapping("/api/v1/jobs")
	public JobOpened open(@CurrentUser UserId userId) {
		return subtitleJobPort.open(userId);
	}

	@PostMapping("/api/v1/jobs/{jobId}/source")
	public StatusResponse receiveSource(@CurrentUser UserId userId, @PathVariable Long jobId) {
		return new StatusResponse(subtitleJobPort.receiveSource(new JobId(jobId), userId));
	}

	@PostMapping("/api/v1/jobs/{jobId}/confirm")
	public StatusResponse confirmScript(@CurrentUser UserId userId, @PathVariable Long jobId) {
		return new StatusResponse(subtitleJobPort.confirmScript(new JobId(jobId), userId));
	}

	@GetMapping("/api/v1/jobs/{jobId}")
	public JobDetail detail(@CurrentUser UserId userId, @PathVariable Long jobId) {
		return subtitleJobPort.detail(new JobId(jobId), userId);
	}

	@GetMapping("/api/v1/jobs")
	public JobList list(@CurrentUser UserId userId) {
		return subtitleJobPort.list(userId);
	}

	record StatusResponse(JobStatus status) {
	}
}
