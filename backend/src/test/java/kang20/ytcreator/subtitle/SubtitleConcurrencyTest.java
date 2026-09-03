package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;
import kang20.ytcreator.subtitle.internal.service.support.SignedUrlIssuer;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class SubtitleConcurrencyTest {

	private static final int THREADS = 8;

	@Autowired
	private SubtitleJobPort subtitleJobPort;

	@Autowired
	private SubtitleWorkerPort subtitleWorkerPort;

	@Autowired
	private JobRepository jobRepository;

	@MockitoBean
	private PaymentUsagePort paymentUsagePort;

	@MockitoBean
	private WorkDispatcher workDispatcher;

	@MockitoBean
	private SignedUrlIssuer signedUrlIssuer;

	@MockitoBean
	private StorageInspector storageInspector;

	@AfterEach
	void 남긴_데이터를_지운다() {
		jobRepository.deleteAll();
	}

	private Job jobAt(JobStatus status) {
		return JobFixture.jobAt(status, jobRepository, JobFixture.OWNER, LocalDateTime.now());
	}

	@Test
	@DisplayName("동시 대본 통지가 몰려도 상태는 한 칸만 나아간다 — 전원이 현재 상태로 수렴한다")
	void 동시_대본_통지가_몰려도_상태는_한_칸만_나아간다() throws InterruptedException {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		when(storageInspector.exists(any())).thenReturn(true);

		List<JobStatus> results = race(i -> subtitleWorkerPort.attachScript(job.getId()));

		assertThat(results).hasSize(THREADS).containsOnly(JobStatus.COMPLETED_SCRIPT);
		Job settled = jobRepository.findById(job.getId()).orElseThrow();
		assertThat(settled.getStatus())
			.as("두 칸 뛰면 사용자 확정 없이 다음 단계가 시작된 것이다")
			.isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(settled.getScript()).isEqualTo(StorageKey.scriptOf(job.getId()));
	}

	@Test
	@DisplayName("동시 확정이 몰려도 산출 의뢰는 한 번이다")
	void 동시_확정이_몰려도_산출_의뢰는_한_번이다() throws InterruptedException {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		when(storageInspector.scriptEmpty(any())).thenReturn(false);

		List<JobStatus> results = race(i -> subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER));

		assertThat(results).hasSize(THREADS).containsOnly(JobStatus.REQUEST_SUBTITLE);
		verify(workDispatcher, timeout(5000).times(1)).dispatch(job.getId(), WorkStage.SUBTITLE);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
			.isEqualTo(JobStatus.REQUEST_SUBTITLE);
	}

	@Test
	@DisplayName("동시 자막 통지가 몰려도 완료는 한 번이고 전원이 수렴한다")
	void 동시_자막_통지가_몰려도_완료는_한_번이고_전원이_수렴한다() throws InterruptedException {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		when(storageInspector.exists(any())).thenReturn(true);

		List<JobStatus> results = race(i -> subtitleWorkerPort.attachSubtitle(job.getId()));

		assertThat(results).hasSize(THREADS).containsOnly(JobStatus.COMPLETED_SUBTITLE);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
			.isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		verify(paymentUsagePort, atLeastOnce()).commit(String.valueOf(job.getId().longValue()));
	}

	private List<JobStatus> race(IntFunction<JobStatus> call) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CyclicBarrier gate = new CyclicBarrier(THREADS);
		List<Future<JobStatus>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < THREADS; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					return call.apply(index);
				}));
			}
		} finally {
			pool.shutdown();
		}
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("60초 안에 끝나야 한다 — 데드락이면 여기서 드러난다")
			.isTrue();

		List<JobStatus> results = new ArrayList<>();
		for (Future<JobStatus> future : futures) {
			try {
				results.add(future.get());
			} catch (ExecutionException e) {
				fail("중복 통지·재확정은 오류가 아니라 현재 상태여야 한다(REQ-136·REQ-138)", e.getCause());
			}
		}
		return results;
	}

	private static void awaitGate(CyclicBarrier gate) {
		try {
			gate.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} catch (BrokenBarrierException | TimeoutException e) {
			throw new IllegalStateException(e);
		}
	}
}
