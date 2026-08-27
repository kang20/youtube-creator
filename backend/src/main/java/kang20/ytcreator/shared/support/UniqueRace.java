package kang20.ytcreator.shared.support;

import java.util.Optional;
import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * <b>UNIQUE 제약을 심판으로 쓰는 동시성 제어 골격</b> — "먼저 쓴 쪽이 이기고, 진 쪽은 승자에게 맞춘다".
 *
 * <p>선점 락도 분산 락도 두지 않는다. 낙관적으로 삽입을 시도하고, UNIQUE 위반이 나면 그것 자체가
 * <b>"누군가 이미 넣었다"는 확정 사실</b>이므로 승자 행을 다시 읽어 결과를 맞춘다. 멱등한 재요청과
 * 동시 요청이 같은 경로로 수렴한다.
 *
 * <p><b>⚠️ 쓰는 쪽이 지켜야 하는 전제 두 가지</b> — 이건 이 유틸이 강제해줄 수 없다:
 * <ol>
 *   <li><b>삽입은 별도 트랜잭션({@code REQUIRES_NEW})에서 flush 까지</b> 끝내야 한다. 호출자와 같은
 *       트랜잭션에서 터지면 영속성 컨텍스트가 오염되고 rollback-only 로 마킹돼 뒤따르는 재조회가
 *       무의미해진다. 그래서 삽입은 {@code @Transactional(REQUIRES_NEW)} 를 단 별도 빈
 *       (예: {@code *LedgerWriter})에 맡긴다.</li>
 *   <li><b>대상 테이블의 UNIQUE 제약이 하나</b>여야 한다. 둘 이상이면 "아무 UNIQUE 위반이나 → 이 행
 *       재조회"가 되어 엉뚱한 제약 위반을 성공으로 오판한다. 제약이 둘인 테이블은 만들지 않는다 —
 *       {@code subscriptions} 가 유일한 예외였으나 {@code UNIQUE(user_id)} 를 걷어내면서 사라졌다
 *       (2026-08-16, 재구독 허용).</li>
 * </ol>
 */
public final class UniqueRace {

	private static final Logger log = LoggerFactory.getLogger(UniqueRace.class);

	private UniqueRace() {
	}

	/**
	 * 삽입을 시도하고, UNIQUE 경쟁에 지면 승자 행으로 결과를 만든다.
	 *
	 * @param attempt 삽입 시도 — 위 전제 ①대로 별도 트랜잭션에서 flush 까지 끝나야 한다
	 * @param settle  경쟁에 졌을 때 승자 행을 읽어 결과를 만든다. 비어 있으면 판정 불가로 보고 원래 예외를 던진다
	 * @param context 판정 불가 로그에 남길 식별자 — 원문 노출이 곤란한 값이면 마스킹해서 넘긴다
	 * @return 이겼으면 {@code attempt} 의 결과, 졌으면 {@code settle} 의 결과
	 * @throws DataIntegrityViolationException UNIQUE 위반이 아니거나, UNIQUE 위반인데 승자 행이 없을 때
	 */
	public static <R> R firstWriterWins(Supplier<R> attempt, Supplier<Optional<R>> settle, Object context) {
		try {
			return attempt.get();
		} catch (DataIntegrityViolationException lost) {
			if (!isUniqueViolation(lost)) {
				throw lost;   // NOT NULL·길이 초과는 경쟁이 아니다 — 삼키면 진짜 버그가 숨는다
			}
			return settle.get().orElseThrow(() -> {
				log.error("[unique-race] UNIQUE 위반인데 승자 행이 없다 — 판정 불가. context={}", context, lost);
				return lost;
			});
		}
	}

	/**
	 * UNIQUE 위반인지 가린다.
	 *
	 * <p>⚠️ JPA 경로는 UNIQUE 위반을 {@code DuplicateKeyException} 으로 세분화하지 않는다
	 * ({@code JdbcTemplate} 경로에만 있다). 그래서 원인 체인의 Hibernate {@code ConstraintKind} 로 좁힌다.
	 */
	public static boolean isUniqueViolation(DataIntegrityViolationException e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE;
			}
		}
		return false;
	}
}
