package kang20.ytcreator.subtitle.internal.service.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-34 · REQ-172(같은 원리) — 실물(객체 스토리지·워커 큐)이 오기 전의 부팅용 임시 어댑터는
 * <b>조용히 성공하면 안 된다.</b> exists 가 조용히 true 를 돌려주면 업로드 확인이,
 * dispatch 가 조용히 삼키면 의뢰가 가짜로 성립한다 — 안전한 쪽은 실패다.
 */
class UnavailableAdapterTest {

	private static final StorageKey KEY = new StorageKey("jobs/1/source");

	@Test
	@DisplayName("임시 저장소 조회는 조용히 성공하지 않는다")
	void 임시_저장소_조회는_조용히_성공하지_않는다() {
		UnavailableStorageInspector inspector = new UnavailableStorageInspector();

		assertThatThrownBy(() -> inspector.exists(KEY)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> inspector.scriptEmpty(KEY)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("임시 처리 의뢰는 조용히 삼키지 않는다")
	void 임시_처리_의뢰는_조용히_삼키지_않는다() {
		assertThatThrownBy(() -> new UnavailableWorkDispatcher().dispatch(new JobId(1L), JobStatus.REQUEST_SCRIPT))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("임시 링크 발급은 가짜 링크를 만들지 않는다")
	void 임시_링크_발급은_가짜_링크를_만들지_않는다() {
		assertThatThrownBy(() -> new UnavailableSignedUrlIssuer().issue(KEY, true))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
