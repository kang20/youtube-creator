package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
import kang20.ytcreator.subtitle.internal.service.support.SignedUrlIssuer;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 워커 의뢰의 아웃박스 경로(subtitle-v3 워커 의뢰 사건 · 재발행) — 전체 컨텍스트라 아웃박스 리스너가
 * 운영과 같이 <b>비동기</b>로 돈다. 큐 장애가 사용자 응답에 새지 않는다는 것은 이 조건에서만 검증된다.
 *
 * <p>발행 레지스트리가 우리 {@code Clock} 을 쓰므로 재발행 지연은 {@link MutableClock} 로 넘긴다.
 * 재발행은 마킹(RESUBMITTED)을 부른 스레드에서 <b>동기로</b> 하고 리스너만 비동기라, "건드리지 않았다"는
 * 발행 상태로 결정적으로 판정한다.
 */
@ActiveProfiles("test")
@SpringBootTest
@Import(SubtitleOutboxTest.OutboxClock.class)
class SubtitleOutboxTest {

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 3, 12, 0, 0);
	private static final Duration WAIT = Duration.ofSeconds(5);

	@TestConfiguration
	static class OutboxClock {

		@Bean
		@Primary
		MutableClock outboxClock() {
			return new MutableClock(BASE);
		}
	}

	@Autowired
	private SubtitleJobPort subtitleJobPort;

	@Autowired
	private SubtitleDispatchPort subtitleDispatchPort;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private EventPublicationRegistry registry;

	@Autowired
	private MutableClock clock;

	@MockitoBean
	private PaymentUsagePort paymentUsagePort;

	@MockitoBean
	private WorkDispatcher workDispatcher;

	@MockitoBean
	private SignedUrlIssuer signedUrlIssuer;

	@MockitoBean
	private StorageInspector storageInspector;

	/** 앞선 테스트가 남긴 발행이 재발행 대상에 섞이면 안 된다 — 미완료는 완료로 접고, 완료는 전부 지운다. */
	@BeforeEach
	void 초기화() {
		jobRepository.deleteAll();
		registry.findIncompletePublications()
			.forEach(publication -> registry.markCompleted(publication.getEvent(), publication.getTargetIdentifier()));
		clock.setTo(BASE.plusDays(1));
		registry.deleteCompletedPublicationsOlderThan(Duration.ZERO);
		clock.setTo(BASE);
	}

	private Job openedJob() {
		Job job = JobFixture.jobAt(JobStatus.CREATED, jobRepository, JobFixture.OWNER, BASE);
		when(storageInspector.exists(StorageKey.sourceOf(job.getId()))).thenReturn(true);
		return job;
	}

	private Optional<TargetEventPublication> publicationOf(Job job) {
		return registry.findIncompletePublications().stream()
			.filter(publication -> publication.getEvent() instanceof WorkRequested requested
				&& requested.jobId() == job.getId().longValue())
			.findFirst();
	}

	private boolean undelivered(Job job) {
		return publicationOf(job).isPresent();
	}

	/** 의뢰는 상태 전이와 같은 트랜잭션의 아웃박스에 남고, 커밋 뒤 큐로 넘어가면 완료로 표시된다 */
	@Test
	@DisplayName("의뢰는 커밋 뒤 아웃박스를 거쳐 큐로 넘어가고 완료로 표시된다")
	void 의뢰는_커밋_뒤_아웃박스를_거쳐_큐로_넘어간다() {
		Job job = openedJob();

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		verify(workDispatcher, timeout(WAIT.toMillis())).dispatch(job.getId(), WorkStage.SCRIPT);
		await().atMost(WAIT).until(() -> !undelivered(job));
	}

	/**
	 * REQ-15 · REQ-170 — 큐 장애는 유실이 아니라 지연이다. 응답은 막히지 않고 상태는 진실로 남으며,
	 * 넘기지 못한 의뢰는 아웃박스에 미완료로 남아 지연이 지난 뒤 재발행된다(폴링 퍼블리셔).
	 */
	@Test
	@DisplayName("큐가 죽어도 응답은 막히지 않고, 의뢰는 아웃박스에 남아 지연 뒤 재발행된다")
	void 큐가_죽어도_응답은_막히지_않고_의뢰는_아웃박스에_남아_재발행된다() {
		Job job = openedJob();
		doThrow(new IllegalStateException("queue unavailable")).doNothing()
			.when(workDispatcher).dispatch(eq(job.getId()), eq(WorkStage.SCRIPT));

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);   // 예외가 새면 사용자 요청이 큐에 묶인 것이다

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		verify(workDispatcher, timeout(WAIT.toMillis())).dispatch(job.getId(), WorkStage.SCRIPT);
		await().atMost(WAIT).until(() -> publicationOf(job)
			.map(publication -> publication.getStatus() == EventPublication.Status.FAILED).orElse(false));

		subtitleDispatchPort.republishUndelivered();   // 지연 전 — 지금 막 넘기는 중일 수 있어 건드리지 않는다
		assertThat(publicationOf(job).orElseThrow().getStatus())
			.as("재발행 지연 전에는 손대지 않는다").isEqualTo(EventPublication.Status.FAILED);

		clock.setTo(BASE.plus(WorkRequested.REPUBLISH_DELAY).plusSeconds(1));
		subtitleDispatchPort.republishUndelivered();

		verify(workDispatcher, timeout(WAIT.toMillis()).times(2)).dispatch(job.getId(), WorkStage.SCRIPT);
		await().atMost(WAIT).until(() -> !undelivered(job));
	}

	/** 재발행할 것이 없으면 큐를 부르지 않는다 — 다른 모듈의 발행을 이 배치가 건드리면 안 된다 */
	@Test
	@DisplayName("재발행할 것이 없으면 큐를 부르지 않는다")
	void 재발행할_것이_없으면_큐를_부르지_않는다() {
		clock.setTo(BASE.plusHours(1));

		subtitleDispatchPort.republishUndelivered();

		verify(workDispatcher, timeout(1000).times(0)).dispatch(any(), any());
	}

	/** 아웃박스 항목은 원시 타입으로 직렬화돼 되살아난다 — 타입 ID 였다면 빈 객체가 저장돼 재발행이 깨진다 */
	@Test
	@DisplayName("재발행된 의뢰도 같은 작업 번호와 단계를 싣는다")
	void 재발행된_의뢰도_같은_작업_번호와_단계를_싣는다() {
		Job job = openedJob();
		doThrow(new IllegalStateException("queue unavailable")).doNothing()
			.when(workDispatcher).dispatch(eq(job.getId()), eq(WorkStage.SCRIPT));
		subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);
		verify(workDispatcher, timeout(WAIT.toMillis())).dispatch(job.getId(), WorkStage.SCRIPT);
		await().atMost(WAIT).until(() -> publicationOf(job)
			.map(publication -> publication.getStatus() == EventPublication.Status.FAILED).orElse(false));

		clock.setTo(BASE.plus(WorkRequested.REPUBLISH_DELAY).plusSeconds(1));
		subtitleDispatchPort.republishUndelivered();

		verify(workDispatcher, timeout(WAIT.toMillis()).times(2)).dispatch(job.getId(), WorkStage.SCRIPT);
		await().atMost(WAIT).until(() -> !undelivered(job));
	}
}
