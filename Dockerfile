# Dockerfile for deploy keycloak but doesn't work in my serv
FROM quay.io/keycloak/keycloak:20.0.0

ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
