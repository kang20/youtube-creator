package kang20.ytcreator;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

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
