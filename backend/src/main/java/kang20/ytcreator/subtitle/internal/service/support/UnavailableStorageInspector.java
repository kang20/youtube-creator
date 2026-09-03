package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

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
