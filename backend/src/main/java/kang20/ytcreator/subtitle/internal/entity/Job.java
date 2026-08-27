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
import kang20.ytcreator.shared.domain.BaseTimeEntity;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JavaType;

@Entity
@Table(name = "jobs", indexes = @Index(name = "ix_jobs_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Job extends BaseTimeEntity {

	/** 사용자 대기 구간(COMPLETED_SCRIPT)의 방치 상한 — 이 초과는 서버 잘못이 아니라 이용권을 되돌리지 않는다. */
	public static final Duration JOB_TIMEOUT = Duration.ofHours(24);

	/** 원본을 지우기까지의 보관 기간 — 만료 배치는 백로그다(subtitle-v1 보관 기간). */
	public static final Period RETENTION = Period.ofMonths(1);

	/** 시스템 구간에서 이 시간을 넘겨 전이가 없으면 멈춘 것이다 — JOB_TIMEOUT(사용자 대기 상한)과 다른 축이다. */
	public static final Duration STALL_THRESHOLD = Duration.ofMinutes(30);

	/** 같은 단계를 다시 시키는 횟수의 상한 — 초과하면 SERVER_FAULT 로 닫고 이용권을 되돌린다. */
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

	// "읽어서 확인한 뒤 쓰기"만으로는 동시 완료 통지 둘이 다 통과한다(subtitle-v1) — 진 쪽은 flush 에서 떨어진다
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
			return true;
		}
		if (status == JobStatus.FAILURE) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);
		}
		return false;   // 재시도 — 이미 나아간 상태를 그대로 돌려준다
	}

	public boolean attachScript(StorageKey script, LocalDateTime now) {
		if (status == JobStatus.REQUEST_SCRIPT) {
			this.script = script;
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
			// 빈 대본은 워커를 거치지 않고 곧장 완료로 간다 — 만들 것이 없다.
			// CONFIRM_SCRIPT 정지는 이벤트 전달 구현에 따라 없어도 된다(subtitle-v1 confirmScript 규칙)
			transition(scriptEmpty ? JobStatus.COMPLETED_SUBTITLE : JobStatus.REQUEST_SUBTITLE, now);
			return true;
		}
		if (status == JobStatus.CONFIRM_SCRIPT || status == JobStatus.REQUEST_SUBTITLE
			|| status == JobStatus.COMPLETED_SUBTITLE) {
			return false;   // 확정 재요청 — 오류가 아니라 현재 상태를 돌려준다
		}
		throw new BusinessException(ErrorCode.SUBTITLE_002);   // 사용자 대기 구간 밖의 확정은 거부한다
	}

	public boolean attachSubtitle(StorageKey subtitle, LocalDateTime now) {
		if (status == JobStatus.REQUEST_SUBTITLE) {
			this.subtitle = subtitle;
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
			case CREATED, REQUEST_SCRIPT, CONFIRM_SCRIPT, REQUEST_SUBTITLE -> exceeded(now, threshold);
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
		if (status != JobStatus.REQUEST_SCRIPT && status != JobStatus.REQUEST_SUBTITLE) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);   // 자기 전이가 있는 상태는 워커 의뢰 구간뿐이다
		}
		if (redispatchCount >= REDISPATCH_LIMIT) {
			return false;
		}
		this.redispatchCount++;
		this.lastTransitionedAt = now;
		return true;
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

	private boolean exceeded(LocalDateTime now, Duration threshold) {
		return Duration.between(lastTransitionedAt, now).compareTo(threshold) > 0;
	}
}
