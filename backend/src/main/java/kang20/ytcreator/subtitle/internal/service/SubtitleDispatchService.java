package kang20.ytcreator.subtitle.internal.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubtitleDispatchService implements SubtitleDispatchPort {

	private final WorkDispatcher workDispatcher;
	private final IncompleteEventPublications incompletePublications;
	private final Clock clock;

	@Override
	public void dispatch(WorkRequested requested) {
		workDispatcher.dispatch(requested.job(), requested.stage());
	}

	@Override
	public void republishUndelivered() {
		Instant bound = clock.instant().minus(WorkRequested.REPUBLISH_DELAY);
		incompletePublications.resubmitIncompletePublications(publication ->
			publication.getEvent() instanceof WorkRequested && publication.getPublicationDate().isBefore(bound));
	}
}
