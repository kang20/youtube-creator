package kang20.ytcreator.auth.dto;

import java.time.LocalDateTime;

/**
 * 등록 결과. <b>PK 를 담지 않는다</b> — 담는 순간 다른 모듈이 auth 의 PK 를 들고 다닌다.
 *
 * @param newUser      이번 호출로 만들어졌으면 true. 동시 등록 경쟁에서 진 경우도 false 다(§6-4)
 * @param registeredAt 등록 시각 = 행 생성 시각
 */
public record Registration(boolean newUser, LocalDateTime registeredAt) {
}
