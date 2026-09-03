package kang20.ytcreator.auth.internal.handler.outbound.repository;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UserId> {

	Optional<User> findByAnonymousKeyHash(String anonymousKeyHash);
}
