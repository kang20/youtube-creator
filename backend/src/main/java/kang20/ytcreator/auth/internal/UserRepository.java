package kang20.ytcreator.auth.internal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 사용자 저장소. 조회 키는 익명키 <b>해시</b>다 — 원문은 DB 에 존재하지 않는다(auth-design.md §3-2). */
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByAnonymousKeyHash(String anonymousKeyHash);
}
