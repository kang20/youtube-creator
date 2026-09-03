package kang20.ytcreator.subtitle.internal.service.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
	@DisplayName("큐가 꺼진 처리 의뢰는 조용히 삼키지 않는다 — 삼키면 아웃박스가 완료로 표시된다")
	void 큐가_꺼진_처리_의뢰는_조용히_삼키지_않는다() {
		assertThatThrownBy(() -> new UnavailableWorkDispatcher().dispatch(new JobId(1L), WorkStage.SCRIPT))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	@DisplayName("임시 링크 발급은 가짜 링크를 만들지 않는다")
	void 임시_링크_발급은_가짜_링크를_만들지_않는다() {
		assertThatThrownBy(() -> new UnavailableSignedUrlIssuer().issue(KEY, true))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
