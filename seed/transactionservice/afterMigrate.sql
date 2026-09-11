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
    IF EXISTS (SELECT 1 FROM transaction_record) THEN
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
    -- Copie du catalogue de seed/tripservice : une transaction fige l'annonce telle
    -- qu'elle etait au moment de la reservation.
    listing(id, seller, departure_airport, arrival_airport, departure_date, duration,
            total_weight, price_per_kg, conditions, created_days_ago) AS (VALUES
        (1, 1, 'CDG', 'JFK', current_date + 5 + time '10:30', interval '8 hours', 23.00, 12.00, 'Pas de liquides ni de produits frais.', 6),
        (2, 1, 'JFK', 'CDG', current_date + 12 + time '18:45', interval '7 hours', 20.00, 11.50, 'Vêtements et petits objets uniquement.', 6),
        (3, 1, 'CDG', 'JFK', current_date - 20 + time '10:30', interval '8 hours', 15.00, 12.00, NULL, 35),
        (4, 1, 'CDG', 'LAX', current_date + 30 + time '13:15', interval '11 hours', 10.00, 15.00, 'Objets fragiles acceptés s''ils sont bien emballés.', 2),
        (5, 2, 'MRS', 'DSS', current_date + 8 + time '07:50', interval '5 hours', 30.00, 6.00, 'Pas de médicaments sans ordonnance. Colis ouverts à la remise.', 10),
        (6, 2, 'DSS', 'MRS', current_date + 22 + time '23:30', interval '5 hours', 25.00, 6.50, 'Produits alimentaires secs acceptés (thé, épices).', 10),
        (7, 2, 'MRS', 'DSS', current_date - 25 + time '07:50', interval '5 hours', 30.00, 5.50, NULL, 40),
        (8, 2, 'ORY', 'DSS', current_date + 3 + time '16:20', interval '5 hours 30 minutes', 5.00, 7.00, 'Petit colis uniquement.', 15),
        (9, 3, 'YUL', 'CDG', current_date + 15 + time '21:00', interval '7 hours', 23.00, 9.00, 'Pas d''objets de valeur (bijoux, électronique).', 4),
        (10, 3, 'CDG', 'YUL', current_date + 40 + time '13:00', interval '8 hours', 23.00, 9.50, NULL, 4),
        (11, 3, 'LYS', 'YUL', current_date - 10 + time '11:10', interval '9 hours', 12.00, 8.00, 'Pas de liquides.', 30),
        (12, 4, 'YUL', 'NRT', current_date + 18 + time '12:40', interval '13 hours', 15.00, 14.00, 'Uniquement des objets déclarables en douane.', 3),
        (13, 4, 'NRT', 'YUL', current_date + 35 + time '17:00', interval '12 hours', 15.00, 13.00, NULL, 3),
        (14, 4, 'YUL', 'CDG', current_date - 5 + time '20:00', interval '7 hours', 20.00, 10.00, NULL, 21),
        (15, 4, 'YUL', 'BCN', current_date + 9 + time '19:30', interval '8 hours', 18.00, 10.50, 'Remise en main propre à l''aéroport uniquement.', 1),
        (16, 5, 'TLS', 'ALG', current_date + 6 + time '09:00', interval '2 hours', 20.00, 5.00, 'Pas de denrées périssables.', 8),
        (17, 5, 'CMN', 'TLS', current_date + 26 + time '15:30', interval '3 hours', 15.00, 5.50, NULL, 8),
        (18, 5, 'TLS', 'ALG', current_date - 15 + time '09:00', interval '2 hours', 20.00, 5.00, 'Pas de denrées périssables.', 45)
    ),
    -- Chaque paire de statuts de TransactionStateMachine apparait au moins une fois.
    state(name, seller_status, buyer_status, paid) AS (VALUES
        ('requested', 'reservation_received', 'waiting_for_response', false),
        ('rejected', 'waiting_for_response_seller', 'request_rejected', false),
        ('accepted', 'awaiting_payment', 'payment_required', false),
        ('confirmed', 'confirmed', 'confirmed', true),
        ('completed', 'completed', 'completed', true),
        ('cancelled', 'cancelled', 'cancelled', false)
    ),
    booking(id, listing_id, buyer, weight, state, created_days_ago, buyer_review, seller_review) AS (VALUES
        (1, 1, 5, 5.00, 'requested', 1, false, false),
        (2, 1, 2, 3.00, 'accepted', 3, false, false),
        (3, 1, 3, 4.00, 'confirmed', 5, false, false),
        (4, 3, 4, 6.00, 'completed', 30, true, true),
        (5, 3, 5, 4.00, 'completed', 28, false, false),
        (6, 5, 5, 10.00, 'requested', 2, false, false),
        (7, 5, 1, 7.00, 'rejected', 4, false, false),
        (8, 5, 4, 5.00, 'cancelled', 6, false, false),
        (9, 7, 5, 12.00, 'completed', 35, true, false),
        (10, 7, 3, 8.00, 'completed', 33, true, true),
        (11, 8, 1, 5.00, 'confirmed', 12, false, false),
        (12, 9, 1, 6.00, 'accepted', 2, false, false),
        (13, 9, 5, 3.00, 'rejected', 3, false, false),
        (14, 11, 4, 5.00, 'completed', 25, true, true),
        (15, 12, 3, 4.00, 'requested', 1, false, false),
        (16, 12, 1, 2.00, 'cancelled', 2, false, false),
        (17, 14, 2, 7.00, 'completed', 18, false, true),
        (18, 16, 2, 8.00, 'confirmed', 4, false, false),
        (19, 16, 4, 4.00, 'requested', 1, false, false),
        (20, 18, 1, 5.00, 'completed', 40, false, false),
        (21, 15, 5, 6.00, 'accepted', 1, false, false)
    )
    INSERT INTO transaction_record (
        id, listing_id, buyer_id, seller_id, seller_status, buyer_status, weight, total,
        seller_review, buyer_review,
        stripe_payment_intent_id, stripe_currency, stripe_amount, paid_at, transaction_created_at,
        buyer_sub, buyer_email, buyer_email_verified, buyer_given_name, buyer_family_name, buyer_name,
        buyer_username, buyer_bio, buyer_location, buyer_phone,
        departure_airport, arrival_airport, departure_date, arrival_date,
        total_weight_available, remaining_weight, price_per_kg, conditions, created_at,
        seller_sub, seller_email, seller_email_verified, seller_given_name, seller_family_name, seller_name,
        seller_username, seller_bio, seller_location, seller_phone)
    SELECT b.id, l.id, bp.sub, sp.sub, s.seller_status, s.buyer_status, b.weight, v.total,
           b.seller_review, b.buyer_review,
           -- Faux paiement Stripe : ce que le webhook signe aurait ecrit.
           CASE WHEN s.paid THEN 'pi_seed_' || lpad(b.id::text, 6, '0') END,
           CASE WHEN s.paid THEN 'eur' END,
           CASE WHEN s.paid THEN (v.total * 100)::bigint END,
           CASE WHEN s.paid THEN v.created_at + interval '1 day' END,
           v.created_at,
           bp.sub, bp.email, true, bp.given_name, bp.family_name, bp.given_name || ' ' || bp.family_name,
           bp.email, bp.bio, bp.location, bp.phone,
           -- Format de LocalDateTime.toString(), comme listingInfoOf() l'ecrit.
           l.departure_airport, l.arrival_airport,
           to_char(l.departure_date, 'YYYY-MM-DD"T"HH24:MI'),
           to_char(l.departure_date + l.duration, 'YYYY-MM-DD"T"HH24:MI'),
           l.total_weight, l.total_weight, l.price_per_kg, l.conditions,
           to_char(now() - make_interval(days => l.created_days_ago), 'YYYY-MM-DD"T"HH24:MI:SS.US'),
           sp.sub, sp.email, true, sp.given_name, sp.family_name, sp.given_name || ' ' || sp.family_name,
           sp.email, sp.bio, sp.location, sp.phone
    FROM booking b
    JOIN listing l ON l.id = b.listing_id
    JOIN state s ON s.name = b.state
    JOIN person bp ON bp.n = b.buyer
    JOIN person sp ON sp.n = l.seller
    CROSS JOIN LATERAL (SELECT round(l.price_per_kg * b.weight, 2) AS total,
                               now() - make_interval(days => b.created_days_ago) AS created_at) v;

    PERFORM setval(pg_get_serial_sequence('transaction_record', 'id'), (SELECT max(id) FROM transaction_record));
END $$;
