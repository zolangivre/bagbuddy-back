# Image Keycloak de production, options de build deja appliquees : sans elle, 'start'
# refait le build Quarkus a chaque creation du conteneur, sur les deux coeurs de la VM.
# Une option de build (base, health, features) se declare ici, pas dans le compose.
FROM quay.io/keycloak/keycloak:25.0.0
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build
