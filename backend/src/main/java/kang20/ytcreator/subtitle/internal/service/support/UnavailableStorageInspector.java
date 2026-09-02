package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

/** 임시 어댑터 — 객체 스토리지 실물이 오면 대체된다. 부팅만 지킨다(subtitle-v3 이용권 연동과 같은 원리). */
@Support
public class UnavailableStorageInspector implements StorageInspector {

	@Override
	public boolean exists(StorageKey key) {
		throw new UnsupportedOperationException("객체 스토리지 구현 전 — 실물을 확인할 수 없다");
	}

	@Override
	public boolean scriptEmpty(StorageKey key) {
		throw new UnsupportedOperationException("객체 스토리지 구현 전 — 대본을 읽을 수 없다");
	}
}
