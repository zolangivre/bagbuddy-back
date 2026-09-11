-- searchTrips filtre par egalite sur les codes IATA : TripService les ecrit desormais en
-- majuscules sans espaces, et les lignes existantes sont remises dans cette forme.
UPDATE trip SET departure_airport = upper(trim(departure_airport))
WHERE departure_airport IS DISTINCT FROM upper(trim(departure_airport));
UPDATE trip SET arrival_airport = upper(trim(arrival_airport))
WHERE arrival_airport IS DISTINCT FROM upper(trim(arrival_airport));

-- La recherche type : un trajet, une fenetre de dates. Le prefixe (departure_airport) sert aussi
-- quand seul le depart est renseigne.
CREATE INDEX IF NOT EXISTS idx_trip_route_departure
    ON trip (departure_airport, arrival_airport, departure_date);
