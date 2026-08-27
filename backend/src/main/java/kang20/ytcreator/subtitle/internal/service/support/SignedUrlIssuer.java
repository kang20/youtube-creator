package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

/**
 * 단명 링크 발급 — 링크 자체가 자격 증명이다. 수명을 짧게 두고, 로그에 남기지 않는다.
 */
@Support
public interface SignedUrlIssuer {

	/** {@code writable} 은 원본 업로드와 확정 전 대본 편집에만 켠다 — 확정되면 수정 권한을 더는 열지 않는다. */
	String issue(StorageKey key, boolean writable);
}
