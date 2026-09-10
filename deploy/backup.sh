#!/bin/sh
# Sauvegarde de toutes les bases de production (Keycloak compris) en un fichier compresse.
# A lancer depuis la racine du depot sur la VM, typiquement par cron (voir README).
#
#   BACKUP_DIR   dossier de destination (defaut : ./backups)
#   KEEP_DAYS    duree de retention locale en jours (defaut : 14)
set -eu

BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%Y-%m-%d_%H%M)"
TARGET="$BACKUP_DIR/bagbuddy_$STAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

# Ecrit d'abord dans un fichier temporaire : un dump interrompu ne doit jamais
# ressembler a une sauvegarde valide.
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
	pg_dumpall --username postgres --clean --if-exists \
	| gzip > "$TARGET.partial"
mv "$TARGET.partial" "$TARGET"

find "$BACKUP_DIR" -name 'bagbuddy_*.sql.gz' -mtime +"$KEEP_DAYS" -delete

echo "Sauvegarde ecrite : $TARGET"
