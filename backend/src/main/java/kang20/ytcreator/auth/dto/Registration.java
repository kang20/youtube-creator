package kang20.ytcreator.auth.dto;

import java.time.LocalDateTime;

/**
 * 등록 결과.
 *
 * <p>{@code newUser=false} 는 "이미 있던 사용자"라는 뜻이며, 동시 등록 경쟁에서 진 경우도
 * 여기에 포함된다(docs/domain/auth-design.md §6-4). 호출자는 둘을 구분하지 않는다.
 *
 * <p>사용자 식별자(PK)는 담지 않는다 — 담는 순간 다른 모듈이 auth 의 PK 를 들고 다니게 된다(§4).
 *
 * @param newUser      이번 호출로 새로 만들어졌으면 true
 * @param registeredAt 등록 시각 = 행 생성 시각(createdAt)
 */
public record Registration(boolean newUser, LocalDateTime registeredAt) {
}
