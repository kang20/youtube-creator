package kang20.ytcreator.subtitle.internal.entity;

import kang20.ytcreator.shared.domain.LongTypeIdentifier;

/**
 * 작업 기본키의 타입 표현 — 다른 모듈이 저장하지 않으므로 internal 에 있다(architecture.md R1).
 * 결제 계열에는 타입이 아니라 불투명 문자열({@code jobRef})로 넘어간다.
 *
 * <p>⚠️ 리플렉션 계약: 박싱 {@code Long} 1개짜리 public 생성자를 유지하고 검증 로직을 넣지 마라 —
 * {@link JobIdJavaType} 와 하이드레이션이 이 생성자를 그대로 탄다(architecture.md 함정 표).
 */
public final class JobId extends LongTypeIdentifier {

	public JobId(Long id) {
		super(id);
	}
}
