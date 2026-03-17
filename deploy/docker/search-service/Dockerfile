FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy Maven wrapper
COPY search-service/.mvn .mvn/
COPY search-service/mvnw .
RUN chmod +x ./mvnw

# Download dependencies first (cached layer)
COPY search-service/pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY search-service/src ./src
RUN ./mvnw package -DskipTests -Dmaven.test.skip=true -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 1001 appgroup && adduser -u 1001 -G appgroup -D appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser
EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
