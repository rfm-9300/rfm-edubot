FROM eclipse-temurin:20-jdk AS build
WORKDIR /app
COPY . .
# Tests run in CI (`./gradlew test`) before this image is built.
RUN ./gradlew buildFatJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
