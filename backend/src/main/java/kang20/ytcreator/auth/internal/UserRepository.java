package kang20.ytcreator.auth.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자 저장소. 각 메서드 호출은 <b>자기 트랜잭션</b>을 갖는다 —
 * AuthService 가 바깥 트랜잭션을 열지 않기 때문에(docs/domain/auth-design.md §6-4)
 * 매 조회가 항상 최신 커밋을 본다.
 *
 * <p>조회 키는 <b>익명키 해시</b>다(§3-2). 원문으로는 조회할 수 없고, 조회할 이유도 없다 —
 * 원문은 DB 에 존재하지 않는다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByAnonymousKeyHash(String anonymousKeyHash);
}
