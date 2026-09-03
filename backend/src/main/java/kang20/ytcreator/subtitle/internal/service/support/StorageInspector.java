package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

@Support
public interface StorageInspector {

	boolean exists(StorageKey key);

	boolean scriptEmpty(StorageKey key);
}
