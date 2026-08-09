package kang20.ytcreator.auth;

import java.util.Optional;
import kang20.ytcreator.auth.dto.Registration;
import kang20.ytcreator.auth.internal.AnonymousKeyHasher;
import kang20.ytcreator.auth.internal.User;
import kang20.ytcreator.auth.internal.UserRepository;
import kang20.ytcreator.auth.internal.UserWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 익명키로 사용자를 등록·식별한다. <b>auth 모듈 밖에서 부를 수 있는 유일한 타입</b>이다.
 *
 * <p>형식 검증은 하지 않는다 — 여기 도달했다는 것은 게이트를 통과했다는 뜻이다(shared/security 소관).
 */
@Service
public class AuthService {

	private final UserRepository userRepository;
	private final UserWriter userWriter;
	private final AnonymousKeyHasher hasher;

	public AuthService(UserRepository userRepository, UserWriter userWriter, AnonymousKeyHasher hasher) {
		this.userRepository = userRepository;
		this.userWriter = userWriter;
		this.hasher = hasher;
	}

	/**
	 * 익명키에 해당하는 사용자를 보장한다(멱등). 흐름과 근거: auth-design.md §5-1·§6-4.
	 *
	 * <p>⚠️ <b>{@code @Transactional} 을 붙이지 마라 — 의도적으로 없다.</b> 바깥 트랜잭션을 열면
	 * MySQL 의 {@code REPEATABLE READ} 스냅샷에 갇혀 재조회가 경쟁자 행을 보지 못한다(§6-2 함정 ④).
	 * <b>H2 에서는 재현되지 않고 운영 MySQL 에서만 터진다.</b> 같은 이유로 호출자도 트랜잭션 안에서
	 * 부르면 안 된다. 불변식은 트랜잭션이 아니라 DB 의 UNIQUE 제약이 지킨다.
	 *
	 * @param anonymousKey 익명키 <b>원문</b> — 즉시 해시로 바뀐다
	 */
	public Registration register(String anonymousKey) {
		String anonymousKeyHash = hasher.hash(anonymousKey);

		Optional<User> existing = userRepository.findByAnonymousKeyHash(anonymousKeyHash);
		if (existing.isPresent()) {
			return new Registration(false, existing.get().getCreatedAt());
		}

		try {
			User created = userWriter.insert(anonymousKeyHash);

			return new Registration(true, created.getCreatedAt());
		} catch (DataIntegrityViolationException e) {
			User winner = userRepository.findByAnonymousKeyHash(anonymousKeyHash).orElseThrow();

			return new Registration(false, winner.getCreatedAt());
		}
	}
}
