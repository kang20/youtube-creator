package kang20.ytcreator.base;

import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyUris;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;

import java.time.Clock;
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

/**
 * 컨트롤러 슬라이스 테스트 공통 베이스. 서브클래스는 @WebMvcTest(XxxController.class) 와
 * 필요한 @MockitoBean 만 추가한다.
 *
 * <p>컨트롤러 테스트 = REST Docs (docs/rule/rest-docs.md). 성공·실패 케이스를 모두 문서화한다.
 *
 * <p><b>(v4) 인증은 {@code Authorization: Bearer} 다</b>(auth.md §5-1 · auth-design.md §14-5 —
 * "기존 컨트롤러 테스트 전부 헤더를 Bearer 로 전환, 베이스에 토큰 발급 헬퍼").
 * 보호 엔드포인트를 부르는 서브클래스는 {@link #bearer(UserId)} 로 헤더 값을 만든다 —
 * 테스트가 별도 토큰 규격을 갖게 되면 계약이 두 벌이 되므로 실제 {@link JwtSupport} 로 발급한다.
 *
 * <p>{@link AuthSliceConfig} 가 필요한 이유: {@code @WebMvcTest} 는 {@code Filter}·
 * {@code HandlerMethodArgumentResolver}·{@code WebMvcConfigurer} 는 스캔하지만
 * {@code JwtSupport}(@Support 내부 부품)와 {@code TokenAuthenticationEntryPoint} 는 스캔하지
 * 않는다 — {@code SecurityConfig} 조립에 필요한 빈을 슬라이스에 직접 공급한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestDocs
@Import({SecurityConfig.class, ControllerTest.AuthSliceConfig.class})
public abstract class ControllerTest {

	// 🔶 도메인 확정 시 실제 운영 호스트로 교체 (vars.env 의 DOMAIN)
	private static final String DOCS_HOST = "api.example.com";

	/** HS256 은 32바이트 이상 키가 필수다(round-1-dev.md 테스터 전제). application-test.yml 과 무관한 슬라이스 전용 값. */
	private static final String TEST_JWT_SECRET = "test-only-hs256-secret-0123456789abcdef!";

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	private JwtSupport jwtSupport;

	/** U8 — 보호 엔드포인트의 자격 증명. 실제 서명 키로 발급하므로 게이트(서명 검증)를 그대로 통과한다. */
	protected String bearer(UserId userId) {
		return "Bearer " + jwtSupport.issue(userId);
	}

	/** 문서의 예시 요청을 운영 호스트로 보이게 고정한다(테스트 실행 위치와 무관하게). */
	protected static OperationRequestPreprocessor requestPreprocessor() {
		return preprocessRequest(prettyPrint(), modifyUris().scheme("https").host(DOCS_HOST).removePort());
	}

	protected static OperationResponsePreprocessor responsePreprocessor() {
		return preprocessResponse(prettyPrint());
	}

	/** {@code @WebMvcTest} 슬라이스에 게이트 조립 부품을 공급한다(클래스 javadoc 참조). */
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
	}
}
