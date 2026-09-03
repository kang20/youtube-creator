package kang20.ytcreator.auth.internal.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.dto.TokenPair;
import kang20.ytcreator.auth.internal.entity.RefreshToken;
import kang20.ytcreator.auth.internal.entity.User;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.auth.internal.service.support.AnonymousKeyHasher;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
import kang20.ytcreator.auth.internal.service.support.RefreshTokenWriter;
import kang20.ytcreator.auth.internal.service.support.UserWriter;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class AuthService implements AuthPort {

	private final UserRepository userRepository;
	private final UserWriter userWriter;
	private final AnonymousKeyHasher hasher;
	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenWriter refreshTokenWriter;
	private final JwtSupport jwtSupport;
	private final Clock clock;

	public AuthService(UserRepository userRepository, UserWriter userWriter, AnonymousKeyHasher hasher,
			RefreshTokenRepository refreshTokenRepository, RefreshTokenWriter refreshTokenWriter,
			JwtSupport jwtSupport, Clock clock) {
		this.userRepository = userRepository;
		this.userWriter = userWriter;
		this.hasher = hasher;
		this.refreshTokenRepository = refreshTokenRepository;
		this.refreshTokenWriter = refreshTokenWriter;
		this.jwtSupport = jwtSupport;
		this.clock = clock;
	}

	@Override
	public LoginResult login(String anonymousKey) {
		Registered registered = register(anonymousKey);

		String accessToken = jwtSupport.issue(registered.userId(), registered.role());
		String refreshToken = refreshTokenWriter.issue(registered.userId(), LocalDateTime.now(clock));

		return new LoginResult(registered.newUser(), registered.registeredAt(), registered.userId(),
			accessToken, refreshToken);
	}

	@Override
	public TokenPair refresh(String refreshToken) {
		String tokenHash = refreshTokenWriter.hash(refreshToken);

		RefreshToken current = refreshTokenRepository.findByTokenHash(tokenHash)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_005));

		LocalDateTime now = LocalDateTime.now(clock);

		if (current.isRevoked()) {
			// 재사용 감지(U9) — 탈취 신호. 그 사용자의 refresh 전부를 폐기하고 거부한다
			refreshTokenWriter.revokeAllByUserId(current.getUserId(), now);
			throw new BusinessException(ErrorCode.AUTH_005);
		}
		if (current.isExpired(now)) {
			throw new BusinessException(ErrorCode.AUTH_005);
		}
		if (refreshTokenWriter.rotate(tokenHash, now) == 0) {
			// 동시 갱신 경쟁 패배 — 한쪽만 새 쌍을 받는다(§14-4)
			throw new BusinessException(ErrorCode.AUTH_005);
		}

		// 권한은 여기서만 DB 에서 다시 읽는다 — 갱신은 원래 DB 를 타는 경로라 U8(요청당 무조회)과
		// 충돌하지 않는다. 승격·강등이 늦어도 30분 뒤 갱신 시점에는 반드시 반영된다는 뜻이기도 하다.
		Role role = userRepository.findById(current.getUserId())
			.map(User::getRole)
			.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_005));

		return new TokenPair(
			jwtSupport.issue(current.getUserId(), role),
			refreshTokenWriter.issue(current.getUserId(), now));
	}

	private Registered register(String anonymousKey) {
		String anonymousKeyHash = hasher.hash(anonymousKey);

		Optional<User> existing = userRepository.findByAnonymousKeyHash(anonymousKeyHash);
		if (existing.isPresent()) {
			return Registered.existing(existing.get());
		}

		try {
			// saveAndFlush 라 채번이 보장된 id 에서 꺼낸다 — 추가 쿼리 없음(payment-design.md §7)
			User created = userWriter.insert(anonymousKeyHash);

			return new Registered(true, created.getCreatedAt(), created.getId(), created.getRole());
		} catch (DataIntegrityViolationException e) {
			User winner = userRepository.findByAnonymousKeyHash(anonymousKeyHash).orElseThrow();

			return Registered.existing(winner);
		}
	}

	private record Registered(boolean newUser, LocalDateTime registeredAt, UserId userId, Role role) {

		static Registered existing(User user) {
			return new Registered(false, user.getCreatedAt(), user.getId(), user.getRole());
		}
	}
}
