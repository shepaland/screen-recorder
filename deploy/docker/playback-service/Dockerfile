FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY playback-service/.mvn .mvn/
COPY playback-service/mvnw .
RUN chmod +x ./mvnw
COPY playback-service/pom.xml .
RUN ./mvnw dependency:go-offline -B
COPY playback-service/src ./src
RUN ./mvnw package -DskipTests -Dmaven.test.skip=true -B

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 1001 appgroup && adduser -u 1001 -G appgroup -D appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
