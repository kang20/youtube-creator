package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

/** 임시 어댑터 — 객체 스토리지 실물이 오면 대체된다. 부팅만 지킨다(subtitle-v1 이용권 연동과 같은 원리). */
@Support
public class UnavailableSignedUrlIssuer implements SignedUrlIssuer {

	@Override
	public String issue(StorageKey key, boolean writable) {
		throw new UnsupportedOperationException("객체 스토리지 구현 전 — 접근 링크를 만들 수 없다");
	}
}
