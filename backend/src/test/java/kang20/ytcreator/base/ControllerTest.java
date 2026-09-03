package kang20.ytcreator.base;

import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyUris;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;

import java.time.Clock;
import kang20.ytcreator.auth.RoleAccessDeniedHandler;
import kang20.ytcreator.auth.TokenAuthenticationEntryPoint;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
import kang20.ytcreator.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.restdocs.operation.preprocess.OperationRequestPreprocessor;
import org.springframework.restdocs.operation.preprocess.OperationResponsePreprocessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@AutoConfigureRestDocs
@Import({SecurityConfig.class, ControllerTest.AuthSliceConfig.class})
public abstract class ControllerTest {

	// 🔶 도메인 확정 시 실제 운영 호스트로 교체 (vars.env 의 DOMAIN)
	private static final String DOCS_HOST = "api.example.com";

	private static final String TEST_JWT_SECRET = "test-only-hs256-secret-0123456789abcdef!";

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	private JwtSupport jwtSupport;

	protected String bearer(UserId userId) {
		return "Bearer " + jwtSupport.issue(userId);
	}

	protected static OperationRequestPreprocessor requestPreprocessor() {
		return preprocessRequest(prettyPrint(), modifyUris().scheme("https").host(DOCS_HOST).removePort());
	}

	protected static OperationResponsePreprocessor responsePreprocessor() {
		return preprocessResponse(prettyPrint());
	}

	@TestConfiguration
	static class AuthSliceConfig {

		@Bean
		JwtSupport jwtSupport() {
			return new JwtSupport(TEST_JWT_SECRET, Clock.systemUTC());
		}

		@Bean
		TokenAuthenticationEntryPoint tokenAuthenticationEntryPoint(ObjectMapper objectMapper) {
			return new TokenAuthenticationEntryPoint(objectMapper);
		}

		@Bean
		RoleAccessDeniedHandler roleAccessDeniedHandler(ObjectMapper objectMapper) {
			return new RoleAccessDeniedHandler(objectMapper);
		}
	}
}
