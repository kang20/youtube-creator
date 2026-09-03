package kang20.ytcreator.shared.support;

import java.util.Optional;
import java.util.function.Supplier;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

public final class UniqueRace {

	private static final Logger log = LoggerFactory.getLogger(UniqueRace.class);

	private UniqueRace() {
	}

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

	public static boolean isUniqueViolation(DataIntegrityViolationException e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE;
			}
		}
		return false;
	}
}
