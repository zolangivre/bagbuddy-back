-- listing_id n'est filtre par aucune requete : la retarification et la reservation
-- sont des appels sortants vers tripservice, pas des lectures de cette table.
-- L'index ne servait donc qu'a alourdir chaque insert et chaque update.
DROP INDEX IF EXISTS idx_transaction_listing_id;
