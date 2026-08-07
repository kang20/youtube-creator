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
 * <p>익명키 형식 검증은 여기서 하지 않는다 — 호출이 여기 도달했다는 것은 이미 필터·게이트를
 * 통과했다는 뜻이다(형식 검증은 shared/security 소관, docs/domain/auth-design.md §5-1).
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
	 * 익명키에 해당하는 사용자를 보장한다(멱등). 같은 익명키로 몇 번을 불러도 사용자는 하나다.
	 *
	 * <p>⚠️ <b>{@code @Transactional} 을 붙이지 마라 — 의도적으로 없다.</b> 바깥 트랜잭션을 열면
	 * MySQL InnoDB 의 {@code REPEATABLE READ} 스냅샷이 첫 조회 시점에 고정되어, 경쟁에서 진 뒤의
	 * 재조회(③)가 경쟁자가 커밋한 행을 보지 못한다(auth-design.md §6-2 함정 ④).
	 * 이 결함은 H2(READ COMMITTED)에서는 재현되지 않고 <b>운영 MySQL 에서만</b> 터진다.
	 * 바깥 트랜잭션은 지키는 불변식도 없다 — 불변식은 DB 의 UNIQUE 제약이 지킨다.
	 *
	 * <p>같은 이유로 <b>호출자도 트랜잭션 안에서 부르면 안 된다</b>(§6-4 전제).
	 *
	 * <p>흐름(§5-1·§6-4):
	 * <pre>
	 *   ⓪ 해시   hasher.hash(원문) — 이후로는 원문을 쓰지 않는다(§3-2)
	 *   ① 조회   있음 → newUser=false
	 *   ② 삽입   성공 → newUser=true                (UserWriter — 별도 빈 · REQUIRES_NEW)
	 *   ③ 재조회 ②가 UNIQUE 위반이면 경쟁자 행을 읽어 newUser=false (에러 아님, 정상 200)
	 * </pre>
	 *
	 * <p>⓪ 이 U6 를 지키는 지점이다: ②의 UNIQUE 위반 메시지에 실리는 값이 <b>해시</b>라
	 * 익명키 원문이 로그에 남지 않는다(§6-4 v2, blockers B4).
	 *
	 * @param anonymousKey 토스 익명키 <b>원문</b> — 이 메서드 안에서 즉시 해시로 바뀐다
	 * @return 등록 결과 — 이번 호출로 만들어졌는지와 등록 시각. 해시도 원문도 담지 않는다
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
			// 경쟁에서 졌다. ②가 UNIQUE 위반을 받았다는 것은 경쟁자가 이미 커밋했다는 뜻이므로
			// 새 트랜잭션인 ③은 그 행을 반드시 본다(§6-4).
			User winner = userRepository.findByAnonymousKeyHash(anonymousKeyHash).orElseThrow();
			return new Registration(false, winner.getCreatedAt());
		}
	}
}
