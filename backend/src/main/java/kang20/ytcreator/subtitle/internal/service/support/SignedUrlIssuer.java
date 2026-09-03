package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

@Support
public interface SignedUrlIssuer {

	String issue(StorageKey key, boolean writable);
}
