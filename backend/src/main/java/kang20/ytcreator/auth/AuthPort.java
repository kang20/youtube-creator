package kang20.ytcreator.auth;

import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.dto.TokenPair;

public interface AuthPort {

	/** {@code @Transactional} 밖에서 불러야 한다 — 바깥 트랜잭션 스냅샷에 갇히면 재조회가 경쟁자 행을 보지 못한다. */
	LoginResult login(String anonymousKey);

	TokenPair refresh(String refreshToken);
}
