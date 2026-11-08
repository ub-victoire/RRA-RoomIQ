FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -pl records-lifecycle-service -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/records-lifecycle-service/target/records-lifecycle-service-1.0.0-SNAPSHOT.jar /app/records-lifecycle-service.jar
USER 10001:10001
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/records-lifecycle-service.jar"]
