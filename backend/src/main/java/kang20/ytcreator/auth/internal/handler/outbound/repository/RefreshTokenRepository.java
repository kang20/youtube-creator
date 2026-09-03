package kang20.ytcreator.auth.internal.handler.outbound.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	@Modifying(clearAutomatically = true)
	@Query("update RefreshToken r set r.revokedAt = :now"
		+ " where r.tokenHash = :tokenHash and r.revokedAt is null")
	int revokeIfActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true)
	@Query("update RefreshToken r set r.revokedAt = :now"
		+ " where r.userId = :userId and r.revokedAt is null")
	int revokeAllByUserId(@Param("userId") UserId userId, @Param("now") LocalDateTime now);
}
