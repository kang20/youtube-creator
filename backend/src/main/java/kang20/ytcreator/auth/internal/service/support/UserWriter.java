package kang20.ytcreator.auth.internal.service.support;

import kang20.ytcreator.auth.internal.entity.User;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.shared.support.Support;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 삽입 전용 빈.
 *
 * <p>⚠️ <b>AuthService 로 합치지 마라.</b> 자기 호출은 프록시를 우회해 {@code REQUIRES_NEW} 가 걸리지
 * 않고, UNIQUE 위반이 호출자 트랜잭션을 rollback-only 로 오염시킨다. 근거: auth-design.md §6-2·§6-4.
 */
@Component
@Support
public class UserWriter {

	private final UserRepository userRepository;

	public UserWriter(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public User insert(String anonymousKeyHash) {
		return userRepository.saveAndFlush(new User(anonymousKeyHash));
	}
}
