package kang20.ytcreator.base;

import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyUris;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;

import kang20.ytcreator.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.context.annotation.Import;
import org.springframework.restdocs.operation.preprocess.OperationRequestPreprocessor;
import org.springframework.restdocs.operation.preprocess.OperationResponsePreprocessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 컨트롤러 슬라이스 테스트 공통 베이스. 서브클래스는 @WebMvcTest(XxxController.class) 와
 * 필요한 @MockitoBean 만 추가한다.
 *
 * <p>컨트롤러 테스트 = REST Docs (docs/rule/rest-docs.md). 성공·실패 케이스를 모두 문서화한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestDocs
@Import(SecurityConfig.class)
public abstract class ControllerTest {

	// 🔶 도메인 확정 시 실제 운영 호스트로 교체 (vars.env 의 DOMAIN)
	private static final String DOCS_HOST = "api.example.com";

	@Autowired
	protected MockMvc mockMvc;

	/** 문서의 예시 요청을 운영 호스트로 보이게 고정한다(테스트 실행 위치와 무관하게). */
	protected static OperationRequestPreprocessor requestPreprocessor() {
		return preprocessRequest(prettyPrint(), modifyUris().scheme("https").host(DOCS_HOST).removePort());
	}

	protected static OperationResponsePreprocessor responsePreprocessor() {
		return preprocessResponse(prettyPrint());
	}
}
