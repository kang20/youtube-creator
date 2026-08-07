package kang20.ytcreator;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계 검증 — 순환 의존·internal 침범·미허용 의존을 전부 잡는다.
 * 이 테스트는 CI 필수다(배포 워크플로의 ./gradlew test 에 포함).
 */
class ModularityTest {

	static final ApplicationModules MODULES = ApplicationModules.of(YtcreatorApplication.class);

	@Test
	void 모듈_경계를_지킨다() {
		MODULES.verify();
	}

	@Test
	void 모듈_문서를_생성한다() {
		new Documenter(MODULES)
			.writeModulesAsPlantUml()
			.writeIndividualModulesAsPlantUml()
			.writeModuleCanvases();     // build/spring-modulith-docs/
	}
}
