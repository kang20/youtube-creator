package kang20.ytcreator.auth.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 삽입 전용 빈.
 *
 * <p>⚠️ <b>AuthService 와 반드시 다른 빈이어야 한다.</b> {@code @Transactional} 은 프록시 기반이라
 * AuthService 안에서 자기 메서드를 부르면(self-invocation) 프록시를 거치지 않아 {@code REQUIRES_NEW} 가
 * 걸리지 않는다. 그러면 UNIQUE 위반이 바깥 트랜잭션을 rollback-only 로 오염시켜
 * 커밋 시점에 {@code UnexpectedRollbackException} 이 터진다
 * (docs/domain/auth-design.md §6-2 함정 ② · §6-4). 이 파일을 AuthService 로 합치지 마라.
 *
 * <p>{@code REQUIRES_NEW} 는 현재 호출 경로(바깥 트랜잭션 없음)에서는 {@code REQUIRED} 와 동작이 같다.
 * 그래도 유지한다 — 나중에 누가 register 를 트랜잭션 안에서 부르더라도 쓰기 실패가
 * 그 트랜잭션을 오염시키지 않게 하는 방어선이다(§6-4).
 */
@Component
public class UserWriter {

	private final UserRepository userRepository;

	public UserWriter(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * 새 사용자를 삽입한다. 같은 해시가 이미 있으면 UNIQUE 위반으로 실패하고
	 * <b>이 트랜잭션만</b> 롤백된다(§6-4 판정 매트릭스 ②).
	 *
	 * <p>{@code saveAndFlush} 인 이유: 삽입 실패를 이 메서드 안에서 확정시켜
	 * Spring 의 {@code DataIntegrityViolationException} 으로 변환된 채 호출자에게 올려보내기 위해서다.
	 *
	 * <p>⚠️ 파라미터는 <b>익명키 해시</b>다(§3-2). 원문을 넘기면 UNIQUE 위반 메시지에 원문이 실려
	 * 로그로 샌다 — 그것이 blockers B4 였다.
	 *
	 * @param anonymousKeyHash {@code AnonymousKeyHasher} 가 만든 SHA-256 hex 64자
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public User insert(String anonymousKeyHash) {
		return userRepository.saveAndFlush(new User(anonymousKeyHash));
	}
}
