# syntax=docker/dockerfile:1

# Etapa 1: build com Maven (o Tailwind é compilado aqui, o node é baixado pelo plugin)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY domain/pom.xml domain/pom.xml
COPY app/pom.xml app/package.json app/package-lock.json* app/
# Baixa as dependências numa camada própria: só refaz quando os pom.xml mudam
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -pl app -am || true
COPY domain domain
COPY app app
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests -Djacoco.skip=true

# Etapa 2: separa o jar em camadas (dependências mudam pouco, o código muda sempre)
FROM eclipse-temurin:25-jre AS layers
WORKDIR /layers
COPY --from=build /src/app/target/self-control-financial.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Etapa 3: imagem final, só o JRE e a aplicação, rodando sem root
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# A imagem base não tem curl/wget: o health check usa o /dev/tcp do bash
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\nHost: localhost\\r\\nX-Forwarded-Proto: https\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
