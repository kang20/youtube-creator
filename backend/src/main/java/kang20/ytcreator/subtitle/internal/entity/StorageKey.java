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

	public static StorageKey scriptOf(JobId jobId) {
		return of(jobId, "script");
	}

	public static StorageKey subtitleOf(JobId jobId) {
		return of(jobId, "subtitle");
	}

	private static StorageKey of(JobId jobId, String name) {
		return new StorageKey("jobs/" + jobId.longValue() + "/" + name);
	}

	@Override
	public String toString() {
		return "StorageKey(***)";
	}
}
