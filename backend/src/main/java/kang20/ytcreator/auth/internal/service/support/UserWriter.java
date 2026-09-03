package kang20.ytcreator.auth.internal.service.support;

import kang20.ytcreator.auth.internal.entity.User;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.shared.support.Support;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
