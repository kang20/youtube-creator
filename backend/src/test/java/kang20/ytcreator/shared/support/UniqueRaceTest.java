package kang20.ytcreator.shared.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * UNIQUE 경쟁 골격 — {@link UniqueRace} 의 네 갈래.
 *
 * <p>실제 경쟁(두 스레드가 동시에 INSERT)은 여기서 재현하지 않는다 — 그건 DB 제약이 심판이므로
 * {@code PaymentServiceTest} 가 진짜 DB로 본다. 여기서는 <b>예외를 어떻게 판정하는가</b>만 본다.
 */
class UniqueRaceTest {

	private static final String WON = "won";
	private static final String SETTLED = "settled";

	/** subscriptions 의 두 제약 — <b>서로의 부분 문자열이 아니어야 한다</b>는 전제의 실물이다. */
	private static final String ORDER_CONSTRAINT = "uk_subscriptions_order_id";
	private static final String USER_CONSTRAINT = "uk_subscriptions_user_id";

	@Test
	@DisplayName("삽입이 성공하면 그 결과를 그대로 돌려준다 — 재조회하지 않는다")
	void 승자() {
		String result = UniqueRace.firstWriterWins(() -> WON, () -> {
			throw new AssertionError("이겼으면 승자 재조회를 하지 않는다");
		}, "ctx");

		assertThat(result).isEqualTo(WON);
	}

	@Test
	@DisplayName("UNIQUE 위반이면 승자 행으로 결과를 만든다")
	void 패자는_승자에게_맞춘다() {
		String result = UniqueRace.firstWriterWins(
			() -> {
				throw violation(ConstraintKind.UNIQUE);
			},
			() -> Optional.of(SETTLED),
			"ctx");

		assertThat(result).isEqualTo(SETTLED);
	}

	/** 🔴 NOT NULL·길이 초과는 경쟁이 아니다 — 삼키면 진짜 버그가 성공으로 위장된다. */
	@Test
	@DisplayName("UNIQUE 위반이 아니면 그대로 터뜨린다 — 승자 재조회를 시도하지 않는다")
	void 경쟁이_아닌_위반은_삼키지_않는다() {
		DataIntegrityViolationException notNull = violation(ConstraintKind.NOT_NULL);

		assertThatThrownBy(() -> UniqueRace.firstWriterWins(
			() -> {
				throw notNull;
			},
			() -> {
				throw new AssertionError("경쟁이 아니면 승자 재조회를 하지 않는다");
			},
			"ctx"))
			.isSameAs(notNull);
	}

	/** 원인 체인에 Hibernate 제약 예외가 아예 없는 경우 — 판정 근거가 없으니 경쟁으로 보지 않는다. */
	@Test
	@DisplayName("제약 종류를 알 수 없으면 경쟁으로 보지 않는다")
	void 판정_근거가_없으면_경쟁이_아니다() {
		DataIntegrityViolationException opaque =
			new DataIntegrityViolationException("정체 불명", new IllegalStateException("원인 불명"));

		assertThatThrownBy(() -> UniqueRace.firstWriterWins(() -> {
			throw opaque;
		}, () -> Optional.of(SETTLED), "ctx")).isSameAs(opaque);
	}

	/**
	 * UNIQUE 위반인데 승자 행이 없다 = 설명되지 않는 상태다. 임의로 성공을 만들지 않고 원래 예외를 던진다
	 * (호출자가 삼키면 무엇이 잘못됐는지 영원히 모른다).
	 */
	@Test
	@DisplayName("UNIQUE 위반인데 승자 행이 없으면 판정 불가로 원래 예외를 던진다")
	void 판정_불가() {
		DataIntegrityViolationException lost = violation(ConstraintKind.UNIQUE);

		assertThatThrownBy(() -> UniqueRace.firstWriterWins(() -> {
			throw lost;
		}, Optional::empty, "ctx")).isSameAs(lost);
	}

	// ── 판정 근거가 없는 무결성 위반 ────────────────────────────────────

	/**
	 * 🔴 <b>Hibernate 제약 예외가 원인 체인에 <u>전혀 없는</u> 무결성 위반은 경쟁이 아니다.</b>
	 *
	 * <p>architecture.md "동시성" 규칙 4 — <b>UNIQUE 위반만 경쟁으로 읽는다.</b> {@code NOT NULL}·길이
	 * 초과도 같은 {@link DataIntegrityViolationException} 으로 올라오고, 드라이버 예외가 Hibernate
	 * {@code ConstraintViolationException} 으로 번역되지 않은 채 올라오는 경로도 있다. 이때
	 * <b>판정 근거가 없으므로 경쟁이 아니다</b>로 떨어져야 한다 — 접어버리면 진짜 버그가
	 * "경쟁에 졌다"로 위장돼 조용히 성공이 된다.
	 */
	@Test
	@DisplayName("원인 체인에 제약 예외가 없는 무결성 위반(NOT NULL·길이 초과)은 경쟁으로 읽지 않는다")
	void 제약_예외가_없는_무결성_위반은_경쟁이_아니다() {
		DataIntegrityViolationException notNull = new DataIntegrityViolationException(
			"Column 'user_id' cannot be null",
			new SQLIntegrityConstraintViolationException("Column 'user_id' cannot be null", "23502", 1048));

		assertThat(UniqueRace.isUniqueViolation(notNull))
			.as("NOT NULL 위반은 UNIQUE 경쟁이 아니다").isFalse();
	}

	/**
	 * 🔴 <b>원인이 아예 없는</b> 무결성 위반(체인 길이 0)도 경쟁이 아니다.
	 * 루프가 한 번도 돌지 못하는 경계다 — 여기서 {@code true} 가 나오면 원인 불명 예외가 전부
	 * 경쟁으로 접힌다.
	 */
	@Test
	@DisplayName("원인이 아예 없는 무결성 위반도 경쟁으로 읽지 않는다")
	void 원인이_없는_무결성_위반은_경쟁이_아니다() {
		DataIntegrityViolationException bare =
			new DataIntegrityViolationException("Data too long for column 'title'");

		assertThat(bare.getCause()).as("전제 — 이 예외는 원인 체인이 비어 있다").isNull();
		assertThat(UniqueRace.isUniqueViolation(bare)).isFalse();
	}

	/**
	 * 🔴 <b>제약 예외 없는 무결성 위반은 승자 재조회 없이 원래 예외 그대로</b> 터져야 한다
	 * (architecture.md 동시성 규칙 4 — 삼키면 진짜 버그가 숨는다).
	 */
	@Test
	@DisplayName("제약 예외 없는 무결성 위반은 재조회 없이 그대로 던진다")
	void 판정_근거가_없으면_삼키지_않는다() {
		DataIntegrityViolationException notNull = new DataIntegrityViolationException(
			"Column 'user_id' cannot be null",
			new SQLIntegrityConstraintViolationException("Column 'user_id' cannot be null", "23502", 1048));

		assertThatThrownBy(() -> UniqueRace.firstWriterWins(
			() -> {
				throw notNull;
			},
			() -> {
				throw new AssertionError("판정 근거가 없으면 승자 재조회를 하지 않는다");
			},
			"ctx"))
			.isSameAs(notNull);
	}

	/**
	 * 🔴 <b>제약 이름을 보지 않는다.</b> 호출자는 payment 지급({@code UNIQUE(order_id)})·credit 잔량
	 * ({@code UNIQUE(user_id)})·subscription 개시({@code UNIQUE(order_id)}) 셋이고, 전부 제약이
	 * 하나뿐인 테이블이라 <b>이름이 무엇이든</b> 경쟁으로 접혀야 한다.
	 */
	@Test
	@DisplayName("제약 이름과 무관하게 UNIQUE 위반이면 접는다")
	void 이름을_보지_않는다() {
		assertThat(UniqueRace.firstWriterWins(() -> {
			throw named(ConstraintKind.UNIQUE, "uk_orders_order_id");
		}, () -> Optional.of(SETTLED), "ctx")).isEqualTo(SETTLED);

		assertThat(UniqueRace.firstWriterWins(() -> {
			throw named(ConstraintKind.UNIQUE, "uk_credit_balance_user_id");
		}, () -> Optional.of(SETTLED), "ctx")).isEqualTo(SETTLED);
	}

	private static DataIntegrityViolationException violation(ConstraintKind kind) {
		return named(kind, "uk_test");
	}

	private static DataIntegrityViolationException named(ConstraintKind kind, String constraintName) {
		return new DataIntegrityViolationException("제약 위반",
			new ConstraintViolationException("제약 위반", new SQLException("sql"), kind, constraintName));
	}
}
