# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /app

COPY pom.xml ./
COPY src ./src

# Cache Maven conserve d'un build a l'autre : sans lui, chaque --build retelechargeait toutes
# les dependances, ce qui se sent sur la VM.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# Jar eclate (application + lib/) : c'est la forme dont la JVM sait archiver les classes.
RUN java -Djarmode=tools -jar target/stripeservice-0.0.1-SNAPSHOT.jar extract --destination /extracted

FROM amazoncorretto:21-alpine

WORKDIR /app

COPY --from=build /extracted/ ./

# AppCDS : un demarrage d'entrainement jusqu'au rafraichissement du contexte, dont la JVM archive
# les classes chargees. Les demarrages suivants les relisent au lieu de les reanalyser, soit
# nettement moins de CPU au boot -- ce qui compte quand huit JVM partagent deux coeurs.
# Rien d'externe n'est joint pendant l'entrainement : ni registre, ni Keycloak. Les valeurs
# factices ne vivent que dans ce RUN.
# Un echec ne bloque pas l'image : elle demarre alors simplement sans archive.
RUN KEYCLOAK_SERVICE_CLIENT_SECRET=cds STRIPE_SECRET_KEY=sk_test_cds STRIPE_WEBHOOK_SECRET=whsec_cds \
    STRIPE_PUBLISHABLE_KEY=pk_test_cds \
    java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
         -Deureka.client.enabled=false \
         -Dmanagement.tracing.enabled=false \
         -jar stripeservice-0.0.1-SNAPSHOT.jar > /tmp/cds-training.log 2>&1 \
    || { echo "CDS training failed, the image will start without a class archive:"; \
         grep -E "APPLICATION FAILED|Caused by|Description:|Exception" /tmp/cds-training.log | head -20; \
         rm -f app.jsa; }

CMD [ "java", "-XX:SharedArchiveFile=app.jsa", "-Xshare:auto", "-jar", "stripeservice-0.0.1-SNAPSHOT.jar"]
