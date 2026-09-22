#!/bin/sh
# Cree une base et un utilisateur proprietaire par consommateur. Execute par l'image
# postgres uniquement quand le volume de donnees est vide (premier demarrage).
set -eu

create_database() {
	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
		-v db="$1" -v owner="$2" -v password="$3" <<-'SQL'
		CREATE USER :"owner" WITH PASSWORD :'password';
		CREATE DATABASE :"db" OWNER :"owner";
		REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
	SQL
}

create_database "$KEYCLOAK_DB" "$KEYCLOAK_DB_USER" "$KEYCLOAK_DB_PASSWORD"
create_database "$TRIP_SERVICE_DB" "$TRIP_SERVICE_USER" "$TRIP_SERVICE_PASSWORD"
create_database "$TRANSACTION_SERVICE_DB" "$TRANSACTION_SERVICE_USER" "$TRANSACTION_SERVICE_PASSWORD"
create_database "$REVIEW_SERVICE_DB" "$REVIEW_SERVICE_USER" "$REVIEW_SERVICE_PASSWORD"
create_database "$USER_SERVICE_DB" "$USER_SERVICE_USER" "$USER_SERVICE_PASSWORD"
