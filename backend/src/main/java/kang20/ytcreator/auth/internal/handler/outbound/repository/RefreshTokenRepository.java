package kang20.ytcreator.auth.internal.handler.outbound.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * refresh 토큰 저장소. 조회 키는 <b>해시</b>다 — 원문은 DB 에 존재하지 않는다(U10).
 * 상태 전이(폐기)는 <b>조건부 UPDATE(JPQL)</b>로만 한다(auth-design.md §14-2·§14-4) —
 * 네이티브 SQL 없음(H2·MySQL 동일 동작).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * 원자 회전(§14-4) — 활성({@code revoked_at IS NULL})일 때만 폐기한다.
	 * 반환 1 = 회전 승자, 0 = 이미 폐기됨(동시 갱신 경쟁 패배 → 호출자가 AUTH_005).
	 */
	@Modifying(clearAutomatically = true)
	@Query("update RefreshToken r set r.revokedAt = :now"
		+ " where r.tokenHash = :tokenHash and r.revokedAt is null")
	int revokeIfActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

	/**
	 * 재사용 감지 시 전체 폐기(U9) — 그 사용자의 활성 refresh 를 전부 죽인다.
	 *
	 * @return 폐기된 행 수
	 */
	@Modifying(clearAutomatically = true)
	@Query("update RefreshToken r set r.revokedAt = :now"
		+ " where r.userId = :userId and r.revokedAt is null")
	int revokeAllByUserId(@Param("userId") UserId userId, @Param("now") LocalDateTime now);
}
