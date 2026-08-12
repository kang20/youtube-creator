package kang20.ytcreator.auth;

import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.dto.TokenPair;

/**
 * 인증 포트 — 익명키 로그인(등록 멱등 + 토큰 발급)과 refresh 회전(auth-design.md §14-2).
 * <b>auth 모듈 밖에서 부를 수 있는 유일한 진입</b>이다(architecture.md "Port·Service·Support 규약").
 * 소비자: bootstrap({@link #login}) · 이 모듈의 {@code AuthTokenController}({@link #refresh} —
 * inbound driving port).
 *
 * <p>v3 까지의 {@code register} 는 v4 에서 {@link #login} 으로 <b>흡수</b>됐다 — 등록 멱등·경쟁 흡수
 * 로직은 그대로이고 토큰 발급이 뒤에 붙는다(auth.md U7 "부트스트랩이 곧 로그인이다").
 *
 * <p>모듈 루트의 public 타입이 공개 계약의 전부다 — 구현체 {@code internal.service.AuthService} 는
 * 다른 모듈이 직접 참조할 수 없다. 다른 모듈은 엔티티가 아니라 <b>타입화된 기본키</b>({@link UserId})만 본다.
 */
public interface AuthPort {

	/**
	 * 익명키로 로그인한다 — 사용자를 보장하고(멱등, U2) access(JWT 30분)·refresh(불투명 14일)를
	 * 발급한다(U7). 흐름: auth-design.md §14-3 (등록부는 §5-1·§6-4 그대로).
	 *
	 * <p>⚠️ <b>{@code @Transactional} 밖에서 불러야 한다.</b> 바깥 트랜잭션을 열면 MySQL 의
	 * {@code REPEATABLE READ} 스냅샷에 갇혀 재조회가 경쟁자 행을 보지 못한다(§6-2 함정 ④).
	 * <b>H2 에서는 재현되지 않고 운영 MySQL 에서만 터진다.</b> 불변식은 트랜잭션이 아니라 UNIQUE 제약이 지킨다.
	 *
	 * <p>재호출 = 재로그인 — 토큰은 호출마다 새로 발급되고 기존 refresh 는 폐기하지 않는다
	 * (다기기 정상 케이스와 구분 불가, auth.md §5-2 · auth-design.md §14-6).
	 *
	 * @param anonymousKey 익명키 <b>원문</b> — 즉시 해시로 바뀐다. 형식 검증(U5)은 호출자 몫이다
	 */
	LoginResult login(String anonymousKey);

	/**
	 * refresh 를 회전한다 — 구 토큰을 폐기하고 새 쌍을 발급한다(U9). 흐름: auth-design.md §14-3.
	 *
	 * <p>폐기된 refresh 의 재제출은 <b>탈취 신호</b>로 간주해 그 사용자의 refresh 전부를 폐기한다(U9).
	 * 동시 갱신 경쟁(같은 refresh 동시 2회)은 조건부 UPDATE 가 한쪽만 통과시킨다(§14-4).
	 *
	 * @param refreshToken refresh <b>원문</b> — 저장은 SHA-256 해시뿐이다(U10)
	 * @throws kang20.ytcreator.shared.exception.BusinessException {@code AUTH_005} —
	 *         만료·미존재·재사용·경쟁 패배 전부 이 한 코드다(auth.md §5-5)
	 */
	TokenPair refresh(String refreshToken);
}
