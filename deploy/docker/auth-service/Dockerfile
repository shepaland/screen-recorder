FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY auth-service/mvnw auth-service/.mvn ./mvnw .mvn/
COPY auth-service/pom.xml .
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B
COPY auth-service/src ./src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
