package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

/**
 * 저장소 조회 — "우리가 판정한다"의 수단이다(subtitle-v1, B1 해소). 읽기만 하고 내려받아 보관하지 않는다.
 * 확인에 실패하면 전이하지 않는다 — 장애 예외를 삼키지 말고 그대로 올린다.
 */
@Support
public interface StorageInspector {

	/** 저장소 키의 실물이 실제로 있는가 — 업로드 완료의 서버 확인. */
	boolean exists(StorageKey key);

	/** 대본 실물을 읽어 자막 글자수가 0인가 — 참이면 산출을 건너뛰고 곧장 완료로 간다. */
	boolean scriptEmpty(StorageKey key);
}
