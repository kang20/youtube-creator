package kang20.ytcreator.auth.internal.handler.outbound.repository;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자 저장소. 조회 키는 익명키 <b>해시</b>다 — 원문은 DB 에 존재하지 않는다(auth-design.md §3-2).
 * ID 제네릭도 타입 ID 다 — youngZZ {@code JpaRepository<AnonymousUser, AnonymousUserId>} 선례.
 */
public interface UserRepository extends JpaRepository<User, UserId> {

	Optional<User> findByAnonymousKeyHash(String anonymousKeyHash);
}
