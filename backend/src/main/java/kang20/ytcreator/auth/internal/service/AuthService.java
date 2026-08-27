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

/**
 * {@link AuthPort} 구현 — 로그인(등록 멱등 + 토큰 발급)과 refresh 회전. auth 모듈의 유일한
 * 오케스트레이터다. {@code internal.service} 라 밖에서 직접 참조할 수 없다 — 소비자는
 * {@link AuthPort} 로만 부른다(architecture.md "Port·Service·Support 규약").
 *
 * <p><b>support 를 참조하는 유일한 주인</b>이다 — {@code @Support} 부품({@link AnonymousKeyHasher}·
 * {@link UserWriter}·{@link JwtSupport}·{@link RefreshTokenWriter})은 이 클래스에서만 불린다.
 *
 * <p>익명키 형식 검증은 하지 않는다 — v4 에서 익명키 수신 지점은 부트스트랩뿐이고 U5 검증은
 * {@code BootstrapController} 가 한다(auth-design.md §14-2).
 */
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

	/**
	 * 로그인 — 기존 register(멱등·경쟁 흡수, §5-1·§6-4) 뒤에 토큰 발급이 붙는다(§14-3).
	 *
	 * <p>⚠️ <b>{@code @Transactional} 을 붙이지 마라 — 의도적으로 없다.</b> 바깥 트랜잭션을 열면
	 * MySQL 의 {@code REPEATABLE READ} 스냅샷에 갇혀 재조회가 경쟁자 행을 보지 못한다(§6-2 함정 ④).
	 * <b>H2 에서는 재현되지 않고 운영 MySQL 에서만 터진다.</b> 같은 이유로 호출자도 트랜잭션 안에서
	 * 부르면 안 된다. 불변식은 트랜잭션이 아니라 DB 의 UNIQUE 제약이 지킨다.
	 *
	 * @param anonymousKey 익명키 <b>원문</b> — 즉시 해시로 바뀐다
	 */
	@Override
	public LoginResult login(String anonymousKey) {
		Registered registered = register(anonymousKey);

		String accessToken = jwtSupport.issue(registered.userId(), registered.role());
		String refreshToken = refreshTokenWriter.issue(registered.userId(), LocalDateTime.now(clock));

		return new LoginResult(registered.newUser(), registered.registeredAt(), registered.userId(),
			accessToken, refreshToken);
	}

	/**
	 * refresh 회전 — §14-3 의사코드 그대로. 트랜잭션 없음 — 원자성은 조건부 UPDATE 가 담당한다(§14-4).
	 *
	 * <p>실패는 전부 {@code AUTH_005} 하나다(auth.md §5-5) — 미존재·재사용·만료·경쟁 패배를
	 * 프론트가 구분할 필요가 없다(행동이 전부 "부트스트랩 재로그인"으로 같다).
	 */
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

	/**
	 * v3 까지의 {@code register} 가 그대로 여기 산다(§5-1·§6-4) — v4 는 이 흐름을 바꾸지 않았다.
	 * 판정 매트릭스(§6-4): 기존 사용자 / 최초 등록 / 경쟁 패배(UNIQUE 위반 흡수 → 재조회).
	 */
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

	/**
	 * 등록 단계의 내부 운반체 — v3 공개 dto {@code Registration} 의 후신. 밖에는 {@link LoginResult} 만 나간다.
	 * {@code role} 은 access 토큰 클레임으로만 쓰인다 — HTTP 응답에 싣지 않는다.
	 */
	private record Registered(boolean newUser, LocalDateTime registeredAt, UserId userId, Role role) {

		static Registered existing(User user) {
			return new Registered(false, user.getCreatedAt(), user.getId(), user.getRole());
		}
	}
}
