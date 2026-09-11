-- Donnees de demonstration, DEV UNIQUEMENT.
--
-- Callback Flyway 'afterMigrate' : joue apres chaque migration, mais n'insere rien
-- si la table contient deja une ligne. Il n'est charge que si SPRING_FLYWAY_LOCATIONS
-- inclut 'filesystem:/seed' (docker-compose.dev.yml le fait, la prod jamais) et ne fait
-- pas partie du jar.
--
-- Les sub correspondent aux comptes de seed/keycloak/bagbuddy-users-0.json, les
-- identifiants d'annonce et de transaction aux autres fichiers de seed/ : tout se
-- tient entre les bases. Les dates sont relatives au jour du premier demarrage, pour
-- que les annonces a venir le restent.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trip) THEN
        RETURN;
    END IF;

    WITH person(n, sub, email, given_name, family_name, bio, location, phone) AS (VALUES
        (1, '5eed0000-0000-4000-8000-000000000001', 'camille.martin@bagbuddy.local', 'Camille', 'Martin',
         'Consultante, je fais Paris–New York deux fois par mois. Ma valise est souvent à moitié vide !',
         'Paris, France', '+33 6 12 45 78 90'),
        (2, '5eed0000-0000-4000-8000-000000000002', 'moussa.diop@bagbuddy.local', 'Moussa', 'Diop',
         'Je rentre à Dakar chaque mois voir la famille, toujours de la place pour dépanner.',
         'Marseille, France', '+33 7 81 23 45 67'),
        (3, '5eed0000-0000-4000-8000-000000000003', 'lea.fontaine@bagbuddy.local', 'Léa', 'Fontaine',
         'Étudiante en master à Montréal, je rentre en France pendant les vacances.',
         'Montréal, Canada', '+1 514 555 0142'),
        (4, '5eed0000-0000-4000-8000-000000000004', 'hugo.tremblay@bagbuddy.local', 'Hugo', 'Tremblay',
         'Photographe, je bouge beaucoup entre le Canada, le Japon et l''Europe.',
         'Montréal, Canada', '+1 438 555 0199'),
        (5, '5eed0000-0000-4000-8000-000000000005', 'ines.benali@bagbuddy.local', 'Inès', 'Benali',
         'J''envoie régulièrement des colis à ma famille à Alger et Casablanca.',
         'Toulouse, France', '+33 6 98 76 54 32')
    ),
    listing(id, seller, departure_airport, arrival_airport, departure_date, duration,
            total_weight, remaining_weight, price_per_kg, conditions, created_days_ago) AS (VALUES
        -- remaining_weight = capacite moins les transactions acceptees, payees ou terminees
        -- de seed/transactionservice (la reservation se fait a l'acceptation).
        (1, 1, 'CDG', 'JFK', current_date + 5 + time '10:30', interval '8 hours', 23.00, 16.00, 12.00, 'Pas de liquides ni de produits frais.', 6),
        (2, 1, 'JFK', 'CDG', current_date + 12 + time '18:45', interval '7 hours', 20.00, 20.00, 11.50, 'Vêtements et petits objets uniquement.', 6),
        (3, 1, 'CDG', 'JFK', current_date - 20 + time '10:30', interval '8 hours', 15.00, 5.00, 12.00, NULL, 35),
        (4, 1, 'CDG', 'LAX', current_date + 30 + time '13:15', interval '11 hours', 10.00, 10.00, 15.00, 'Objets fragiles acceptés s''ils sont bien emballés.', 2),
        (5, 2, 'MRS', 'DSS', current_date + 8 + time '07:50', interval '5 hours', 30.00, 30.00, 6.00, 'Pas de médicaments sans ordonnance. Colis ouverts à la remise.', 10),
        (6, 2, 'DSS', 'MRS', current_date + 22 + time '23:30', interval '5 hours', 25.00, 25.00, 6.50, 'Produits alimentaires secs acceptés (thé, épices).', 10),
        (7, 2, 'MRS', 'DSS', current_date - 25 + time '07:50', interval '5 hours', 30.00, 10.00, 5.50, NULL, 40),
        (8, 2, 'ORY', 'DSS', current_date + 3 + time '16:20', interval '5 hours 30 minutes', 5.00, 0.00, 7.00, 'Petit colis uniquement.', 15),
        (9, 3, 'YUL', 'CDG', current_date + 15 + time '21:00', interval '7 hours', 23.00, 17.00, 9.00, 'Pas d''objets de valeur (bijoux, électronique).', 4),
        (10, 3, 'CDG', 'YUL', current_date + 40 + time '13:00', interval '8 hours', 23.00, 23.00, 9.50, NULL, 4),
        (11, 3, 'LYS', 'YUL', current_date - 10 + time '11:10', interval '9 hours', 12.00, 7.00, 8.00, 'Pas de liquides.', 30),
        (12, 4, 'YUL', 'NRT', current_date + 18 + time '12:40', interval '13 hours', 15.00, 15.00, 14.00, 'Uniquement des objets déclarables en douane.', 3),
        (13, 4, 'NRT', 'YUL', current_date + 35 + time '17:00', interval '12 hours', 15.00, 15.00, 13.00, NULL, 3),
        (14, 4, 'YUL', 'CDG', current_date - 5 + time '20:00', interval '7 hours', 20.00, 13.00, 10.00, NULL, 21),
        (15, 4, 'YUL', 'BCN', current_date + 9 + time '19:30', interval '8 hours', 18.00, 12.00, 10.50, 'Remise en main propre à l''aéroport uniquement.', 1),
        (16, 5, 'TLS', 'ALG', current_date + 6 + time '09:00', interval '2 hours', 20.00, 12.00, 5.00, 'Pas de denrées périssables.', 8),
        (17, 5, 'CMN', 'TLS', current_date + 26 + time '15:30', interval '3 hours', 15.00, 15.00, 5.50, NULL, 8),
        (18, 5, 'TLS', 'ALG', current_date - 15 + time '09:00', interval '2 hours', 20.00, 15.00, 5.00, 'Pas de denrées périssables.', 45)
    )
    INSERT INTO trip (id, user_id, departure_airport, arrival_airport, departure_date, arrival_date,
                      total_weight_available, remaining_weight, price_per_kg, active, conditions, created_at,
                      sub, email, email_verified, given_name, family_name, name, username, bio, location, phone)
    SELECT l.id, p.sub, l.departure_airport, l.arrival_airport, l.departure_date, l.departure_date + l.duration,
           l.total_weight, l.remaining_weight, l.price_per_kg,
           -- Meme formule que TripListener, qui ne s'execute pas sur un INSERT SQL.
           l.remaining_weight > 0 AND l.departure_date > now(),
           l.conditions, now() - make_interval(days => l.created_days_ago),
           p.sub, p.email, true, p.given_name, p.family_name, p.given_name || ' ' || p.family_name, p.email,
           p.bio, p.location, p.phone
    FROM listing l
    JOIN person p ON p.n = l.seller;

    PERFORM setval(pg_get_serial_sequence('trip', 'id'), (SELECT max(id) FROM trip));
END $$;

-- Reservations des transactions acceptees, payees ou terminees de seed/transactionservice :
-- ce sont elles que remaining_weight ci-dessus a deja deduites. Bloc separe, pour qu'une base
-- de dev semee avant l'arrivee de trip_reservation recoive aussi ses lignes. Rien n'est insere
-- si les annonces ne sont pas celles du seed.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trip_reservation)
       OR NOT EXISTS (SELECT 1 FROM trip WHERE id = 1 AND user_id = '5eed0000-0000-4000-8000-000000000001') THEN
        RETURN;
    END IF;

    INSERT INTO trip_reservation (trip_id, transaction_id, weight, created_at, released_at)
    SELECT r.trip_id, r.transaction_id, r.weight, now() - make_interval(days => r.created_days_ago), NULL
    FROM (VALUES
        -- (annonce, transaction, poids, jours) : memes valeurs que booking(...) cote transactions.
        (1, 2, 3.00, 3),
        (1, 3, 4.00, 5),
        (3, 4, 6.00, 30),
        (3, 5, 4.00, 28),
        (7, 9, 12.00, 35),
        (7, 10, 8.00, 33),
        (8, 11, 5.00, 12),
        (9, 12, 6.00, 2),
        (11, 14, 5.00, 25),
        (14, 17, 7.00, 18),
        (15, 21, 6.00, 1),
        (16, 18, 8.00, 4),
        (18, 20, 5.00, 40)
    ) AS r(trip_id, transaction_id, weight, created_days_ago);
END $$;
