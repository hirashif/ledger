# build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/ledger-0.1.0.jar app.jar
# these three cut Spring's cold start on a scale-to-zero host by roughly half
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dspring.main.lazy-initialization=true"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
