FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/dist/classes /app/classes
COPY target/dist/lib /app/lib
RUN mkdir -p /app/data && chown -R 65532:65532 /app/data
USER 65532:65532
ENTRYPOINT ["java","-cp","/app/classes:/app/lib/*","com.yjiyun.chatflows.manager.ManagerApplication"]
