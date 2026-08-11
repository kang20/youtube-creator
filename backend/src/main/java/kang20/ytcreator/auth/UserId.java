package kang20.ytcreator.auth;

import kang20.ytcreator.shared.domain.LongTypeIdentifier;

/**
 * 사용자 기본키의 타입 표현 — auth 가 밖에 노출하는 <b>유일한 사용자 식별자</b>다
 * (payment-design.md §2-1 쟁점 1 · architecture.md "타입화된 기본키").
 *
 * <p>다른 모듈은 {@code User} 엔티티가 아니라 이 타입만 저장·전달한다. FK 컬럼 매핑은
 * {@link UserIdJavaType}(BIGINT 값 컬럼) — 연관관계가 아니다.
 *
 * <p>⚠️ <b>리플렉션 계약</b>: 박싱 {@code Long} 1개짜리 public 생성자를 유지하고
 * 검증 로직을 넣지 마라 — Hibernate 하이드레이션이 이 생성자를 그대로 탄다(architecture.md 함정 표).
 */
public final class UserId extends LongTypeIdentifier {

	public UserId(Long id) {
		super(id);
	}
}
