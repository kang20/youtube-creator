package kang20.ytcreator.subtitle.internal.entity;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Period;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.shared.domain.AggregateRootEntity;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JavaType;

@Entity
@Table(name = "jobs", indexes = @Index(name = "ix_jobs_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Job extends AggregateRootEntity<Job> {

	public static final Duration JOB_TIMEOUT = Duration.ofHours(24);

	public static final Period RETENTION = Period.ofMonths(1);

	public static final Duration STALL_THRESHOLD = Duration.ofMinutes(30);

	public static final int REDISPATCH_LIMIT = 3;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@JavaType(JobIdJavaType.class)
	private JobId id;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private JobStatus status;

	@Embedded
	@AttributeOverride(name = "value", column = @Column(name = "source_key", length = 512))
	private StorageKey source;

	@Embedded
	@AttributeOverride(name = "value", column = @Column(name = "script_key", length = 512))
	private StorageKey script;

	@Embedded
	@AttributeOverride(name = "value", column = @Column(name = "subtitle_key", length = 512))
	private StorageKey subtitle;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_cause", length = 16)
	private FailureCause failureCause;

	@Column(name = "last_transitioned_at", nullable = false)
	private LocalDateTime lastTransitionedAt;

	@Column(name = "expired_at")
	private LocalDateTime expiredAt;

	@Column(name = "redispatch_count", nullable = false)
	private int redispatchCount;

	// "읽어서 확인한 뒤 쓰기"만으로는 동시 완료 통지 둘이 다 통과한다(subtitle-v3) — 진 쪽은 flush 에서 떨어진다
	@Version
	private Long version;

	private Job(UserId userId, LocalDateTime now) {
		this.userId = userId;
		this.status = JobStatus.CREATED;
		this.lastTransitionedAt = now;
	}

	public static Job open(UserId userId, LocalDateTime now) {
		return new Job(userId, now);
	}

	public boolean receiveSource(LocalDateTime now) {
		if (status == JobStatus.CREATED) {
			this.source = StorageKey.sourceOf(id);
			transition(JobStatus.REQUEST_SCRIPT, now);
			requestWork(WorkStage.SCRIPT);
			return true;
		}
		if (status == JobStatus.FAILURE) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);
		}
		return false;   // 재시도 — 이미 나아간 상태를 그대로 돌려준다
	}

	public boolean attachScript(LocalDateTime now) {
		if (status == JobStatus.REQUEST_SCRIPT) {
			this.script = StorageKey.scriptOf(id);
			transition(JobStatus.COMPLETED_SCRIPT, now);
			return true;
		}
		if (status == JobStatus.CREATED) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);   // 의뢰한 적 없는 완료 통지
		}
		return false;   // 이미 지난 단계·닫힌 작업의 통지는 오류가 아니다 — 무시한다
	}

	public boolean confirmScript(boolean scriptEmpty, LocalDateTime now) {
		if (status == JobStatus.COMPLETED_SCRIPT) {
			if (scriptEmpty) {
				transition(JobStatus.COMPLETED_SUBTITLE, now);   // 빈 대본은 만들 것이 없다 — 워커를 거치지 않는다
			} else {
				transition(JobStatus.REQUEST_SUBTITLE, now);
				requestWork(WorkStage.SUBTITLE);
			}
			return true;
		}
		if (status == JobStatus.REQUEST_SUBTITLE || status == JobStatus.COMPLETED_SUBTITLE) {
			return false;   // 확정 재요청 — 오류가 아니라 현재 상태를 돌려준다
		}
		throw new BusinessException(ErrorCode.SUBTITLE_002);   // 사용자 대기 구간 밖의 확정은 거부한다
	}

	public boolean attachSubtitle(LocalDateTime now) {
		if (status == JobStatus.REQUEST_SUBTITLE) {
			this.subtitle = StorageKey.subtitleOf(id);
			transition(JobStatus.COMPLETED_SUBTITLE, now);
			return true;
		}
		if (status == JobStatus.COMPLETED_SUBTITLE || status == JobStatus.FAILURE) {
			return false;
		}
		throw new BusinessException(ErrorCode.SUBTITLE_002);   // 의뢰한 적 없는 완료 통지
	}

	public boolean fail(FailureCause cause, LocalDateTime now) {
		if (status == JobStatus.COMPLETED_SUBTITLE) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);   // 완료된 작업은 실패로 가지 않는다
		}
		if (status == JobStatus.FAILURE) {
			return false;
		}
		this.failureCause = cause;
		transition(JobStatus.FAILURE, now);
		return true;
	}

	/** 시스템 구간에서 임계 시간을 넘겨 멈췄는가 — 사용자 대기(COMPLETED_SCRIPT)와 종결 상태는 판정 대상이 아니다. */
	public boolean stalled(LocalDateTime now, Duration threshold) {
		return switch (status) {
			case CREATED, REQUEST_SCRIPT, REQUEST_SUBTITLE -> exceeded(now, threshold);
			case COMPLETED_SCRIPT, COMPLETED_SUBTITLE, FAILURE -> false;
		};
	}

	/** 사용자 대기 구간에서 작업 타임아웃(24h)을 넘겼는가 — 멈춘 것이 아니라 방치다. */
	public boolean abandoned(LocalDateTime now) {
		return status == JobStatus.COMPLETED_SCRIPT && exceeded(now, JOB_TIMEOUT);
	}

	/**
	 * 멈춘 그 단계를 다시 시킨다 — 상태도의 자기 전이라 전이 시각을 새로 찍어 재개 창을 다시 연다.
	 * 한계(REDISPATCH_LIMIT)를 다 썼으면 false — 호출자가 SERVER_FAULT 로 닫는다.
	 */
	public boolean redispatch(LocalDateTime now) {
		WorkStage stage = requestedStage();
		if (redispatchCount >= REDISPATCH_LIMIT) {
			return false;
		}
		this.redispatchCount++;
		this.lastTransitionedAt = now;
		requestWork(stage);
		return true;
	}

	/** 지금 워커에게 시켜 둔 단계 — 자기 전이가 있는 상태는 워커 의뢰 구간뿐이다. */
	public WorkStage requestedStage() {
		return switch (status) {
			case REQUEST_SCRIPT -> WorkStage.SCRIPT;
			case REQUEST_SUBTITLE -> WorkStage.SUBTITLE;
			default -> throw new BusinessException(ErrorCode.SUBTITLE_002);
		};
	}

	public boolean ownedBy(UserId userId) {
		return this.userId.equals(userId);
	}

	/** 원본을 지운 사실만 기록한다 — 상태는 그대로 둔다(만료는 상태가 아니라 별도 축이다). */
	public boolean expire(LocalDateTime now) {
		if (expiredAt != null) {
			return false;
		}
		this.expiredAt = now;
		return true;
	}

	public boolean expired() {
		return expiredAt != null;
	}

	private void transition(JobStatus next, LocalDateTime now) {
		this.status = next;
		this.lastTransitionedAt = now;   // 상태가 바뀔 때만 갱신한다 — 조회로 갱신하면 멈춘 작업이 영원히 안 잡힌다
	}

	// 등록만 한다 — 발행은 리포지토리 저장이 같은 트랜잭션의 아웃박스에 남기며 한다(AggregateRootEntity)
	private void requestWork(WorkStage stage) {
		registerEvent(WorkRequested.of(id, stage));
	}

	private boolean exceeded(LocalDateTime now, Duration threshold) {
		return Duration.between(lastTransitionedAt, now).compareTo(threshold) > 0;
	}
}
