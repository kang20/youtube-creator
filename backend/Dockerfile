# 운영 런타임 이미지 — JAR 은 GitHub Actions 에서 빌드해 넘겨받는다.
# buildx --platform linux/arm64 로 빌드 (OCI Ampere A1 = ARM).
FROM eclipse-temurin:25-jre

WORKDIR /app

# bootJar 산출물만 복사. *-plain.jar 은 glob 에 걸리지 않는다.
COPY build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080

# 컨테이너 메모리 한도 기준으로 힙을 잡는다 (compose 의 mem_limit 와 연동)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
