package kang20.ytcreator.auth;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentUser} 파라미터에 {@link UserAuthentication} 의 {@link UserId} 를 주입한다
 * (auth-design.md §14-2, youngZZ 리졸버 패턴). 등록은 {@code config/WebConfig} 가 한다.
 *
 * <p><b>인가는 하지 않는다</b> — 인증 필요 경로는 default-deny 체인이 이미 막았으므로, 여기 도달한
 * 요청은 {@link UserAuthentication} 을 갖는 것이 정상이다. youngZZ 와 달리 DB 게이트도 없다(주입 전용).
 *
 * <p>인증이 없는데 {@code @CurrentUser} 를 만나는 것은 <b>공개 경로에 이 어노테이션을 잘못 붙인
 * 개발 오류</b>다 — 조용히 null 을 주입하는 대신 {@code AUTH_001} 로 드러낸다.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentUser.class)
			&& UserId.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof UserAuthentication userAuthentication) {
			return userAuthentication.getUserId();
		}
		throw new BusinessException(ErrorCode.AUTH_001);
	}
}
