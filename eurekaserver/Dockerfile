FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copier d'abord le pom.xml seul pour profiter du cache Docker
COPY pom.xml ./

# Télécharger les dépendances (sera mis en cache si pom.xml ne change pas)
RUN mvn dependency:go-offline -B || mvn dependency:resolve -B

# Copier le code source
COPY src ./src

# Builder avec plus d'options pour gérer les timeouts
RUN mvn clean package -DskipTests -B \
    -Dmaven.wagon.http.retryHandler.count=3 \
    -Dmaven.wagon.httpconnectionManager.ttlSeconds=120

FROM amazoncorretto:21-alpine

WORKDIR /app
COPY --from=build /app/target/eurekaserver-0.0.1-SNAPSHOT.jar ./

EXPOSE 8761

CMD [ "java", "-jar", "eurekaserver-0.0.1-SNAPSHOT.jar"]