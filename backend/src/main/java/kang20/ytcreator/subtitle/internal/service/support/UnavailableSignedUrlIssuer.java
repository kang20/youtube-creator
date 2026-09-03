package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

@Support
public class UnavailableSignedUrlIssuer implements SignedUrlIssuer {

	@Override
	public String issue(StorageKey key, boolean writable) {
		throw new UnsupportedOperationException("객체 스토리지 구현 전 — 접근 링크를 만들 수 없다");
	}
}
