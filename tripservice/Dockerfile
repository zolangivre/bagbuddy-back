FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /app

COPY pom.xml ./
COPY src ./src

RUN mvn clean package -DskipTests

FROM amazoncorretto:21-alpine

WORKDIR /app

COPY --from=build /app/target/tripservice-0.0.1-SNAPSHOT.jar ./

CMD [ "java", "-jar", "tripservice-0.0.1-SNAPSHOT.jar"]