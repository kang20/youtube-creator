package kang20.ytcreator.subtitle.internal.entity;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.Embeddable;

@Embeddable
public record StorageKey(String value) {

	public StorageKey {
		requireNonNull(value, "저장소 키는 비어 있을 수 없다");
		if (value.isBlank()) {
			throw new IllegalArgumentException("저장소 키는 비어 있을 수 없다");
		}
	}

	public static StorageKey sourceOf(JobId jobId) {
		return of(jobId, "source");
	}

	/** 워커가 아니라 서버가 위치를 정한다 — 위치가 결정적이어야 존재를 물을 수 있고, 존재가 곧 멱등 키다. */
	public static StorageKey scriptOf(JobId jobId) {
		return of(jobId, "script");
	}

	public static StorageKey subtitleOf(JobId jobId) {
		return of(jobId, "subtitle");
	}

	private static StorageKey of(JobId jobId, String name) {
		return new StorageKey("jobs/" + jobId.longValue() + "/" + name);
	}

	/** 위치는 밖으로 내려주지 않는다 — 로그·예외 메시지에 원문이 실리지 않게 여기서 강제한다. */
	@Override
	public String toString() {
		return "StorageKey(***)";
	}
}
