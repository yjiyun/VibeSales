FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
COPY docs ./docs
COPY scripts ./scripts
COPY README.md ./
RUN mvn -B -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dist/lib

FROM eclipse-temurin:17.0.12_7-jre
WORKDIR /app

COPY --from=build /src/target/classes /app/classes
COPY --from=build /src/target/dist/lib /app/lib
USER 65532:65532

EXPOSE 8088
HEALTHCHECK --interval=10s --timeout=4s --start-period=30s --retries=12 CMD ["java","-cp","/app/classes:/app/lib/*","com.yjiyun.chatflows.runtime.RuntimeHealthCheck"]
ENTRYPOINT ["java","-cp","/app/classes:/app/lib/*","com.yjiyun.chatflows.runtime.RuntimeApplication"]
