package kang20.ytcreator.credit.internal.port;

import kang20.ytcreator.auth.UserId;

public interface CreditGrantPort {

	void grant(UserId userId);
}
