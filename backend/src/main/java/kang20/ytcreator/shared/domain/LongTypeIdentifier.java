package kang20.ytcreator.shared.domain;

import java.io.Serializable;

/**
 * Long 기본키의 타입화 공통 부모. 규칙 정본: docs/rule/architecture.md "타입화된 기본키".
 *
 * <p>모듈이 밖에 노출하는 것은 엔티티가 아니라 이걸 상속한 <b>구체 ID 타입뿐</b>이다
 * (예: {@code auth/UserId}). 원시 {@code Long} 을 주고받으면 "어느 도메인의 Long 인가"가
 * 시그니처에서 사라진다 — 혼용은 컴파일러가 잡아야 한다(payment-design.md §2-1 쟁점 1).
 *
 * <p>⚠️ <b>리플렉션 계약</b>: 구체 ID 는 박싱 {@code Long} 1개짜리 <b>public 생성자</b>가 필수다 —
 * {@link LongTypeIdentifierJavaType} 의 wrap/fromString 이 리플렉션으로 그 생성자를 부른다.
 * Hibernate 하이드레이션이 같은 생성자를 그대로 타므로 <b>생성자에 검증 로직을 넣지 마라</b>
 * (architecture.md 함정 표).
 */
public abstract class LongTypeIdentifier extends ValueObject<LongTypeIdentifier> implements Serializable {

	private final Long id;

	protected LongTypeIdentifier(Long id) {
		this.id = id;
	}

	/** 언랩 접근자 — 네이티브 쿼리 등 JavaType 이 닿지 않는 곳 한정으로 쓴다(architecture.md). */
	public Long longValue() {
		return id;
	}

	@Override
	protected Object[] getEqualityFields() {
		return new Object[] {id};
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName() + "(" + longValue() + ")";
	}
}
