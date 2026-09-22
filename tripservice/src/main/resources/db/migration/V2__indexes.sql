-- Les colonnes que les requetes filtrent reellement. Sans ces index, chaque
-- recherche etait un parcours complet de table.

-- tripsByUser / payoutAccount, tries par date de creation.
CREATE INDEX IF NOT EXISTS idx_trip_user_id_created_at ON trip (user_id, created_at DESC);

-- activeTrips / inactiveTrips filtrent sur (remaining_weight, departure_date).
-- Volontairement pas d'index sur la colonne 'active' : elle n'est recalculee
-- qu'a l'ecriture et devient fausse des qu'une date de depart est passee.
CREATE INDEX IF NOT EXISTS idx_trip_departure_date ON trip (departure_date);

CREATE INDEX IF NOT EXISTS idx_trip_created_at ON trip (created_at DESC);
