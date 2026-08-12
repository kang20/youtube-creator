package kang20.ytcreator.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 게이트가 확정한 {@link UserId} 를 주입한다(auth-design.md §14-2).
 *
 * <pre>{@code @PostMapping("/grant") GrantResult grant(@CurrentUser UserId userId, ...)}</pre>
 *
 * <p>해석은 {@link CurrentUserArgumentResolver} — <b>인가는 하지 않는다</b>(default-deny 체인이 함).
 * 파라미터 타입은 {@link UserId} 여야 한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUser {
}
