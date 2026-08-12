package kang20.ytcreator.auth;

import kang20.ytcreator.auth.dto.Registration;

/**
 * 인증 <b>등록 포트</b> — 익명키로 사용자를 보장한다(멱등). <b>auth 모듈 밖에서 부를 수 있는 유일한 진입</b>이다
 * (architecture.md "Port·Service·Support 규약"). 소비자: bootstrap(진입) · payment(첫 결제 자동 등록).
 *
 * <p>모듈 루트의 {@code *Port} 인터페이스가 공개 계약의 전부다 — 구현체
 * {@code internal.service.AuthService} 는 다른 모듈이 직접 참조할 수 없다. 다른 모듈은 엔티티가 아니라
 * <b>타입화된 기본키</b>({@link UserId})만 본다(auth-design v3).
 */
public interface AuthPort {

	/**
	 * 익명키에 해당하는 사용자를 보장한다(멱등). 흐름과 근거: auth-design.md §5-1·§6-4.
	 *
	 * <p>⚠️ <b>{@code @Transactional} 밖에서 불러야 한다.</b> 바깥 트랜잭션을 열면 MySQL 의
	 * {@code REPEATABLE READ} 스냅샷에 갇혀 재조회가 경쟁자 행을 보지 못한다(§6-2 함정 ④).
	 * <b>H2 에서는 재현되지 않고 운영 MySQL 에서만 터진다.</b> 불변식은 트랜잭션이 아니라 UNIQUE 제약이 지킨다.
	 *
	 * @param anonymousKey 익명키 <b>원문</b> — 즉시 해시로 바뀐다
	 */
	Registration register(String anonymousKey);
}
