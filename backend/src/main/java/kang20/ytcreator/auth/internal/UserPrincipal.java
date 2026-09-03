package kang20.ytcreator.auth.internal;

import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.UserId;

public record UserPrincipal(UserId userId, Role role) {
}
