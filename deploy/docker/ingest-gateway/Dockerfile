FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy Maven wrapper
COPY ingest-gateway/.mvn .mvn/
COPY ingest-gateway/mvnw .
RUN chmod +x ./mvnw

# Download dependencies first (cached layer)
COPY ingest-gateway/pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY ingest-gateway/src ./src
RUN ./mvnw package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 1001 appgroup && adduser -u 1001 -G appgroup -D appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser
EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]
