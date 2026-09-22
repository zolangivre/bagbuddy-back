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
    IF EXISTS (SELECT 1 FROM review) THEN
        RETURN;
    END IF;

    -- Seules des transactions terminees sont notees, et leurs drapeaux buyer_review /
    -- seller_review dans seed/transactionservice correspondent a ces lignes.
    WITH person(n, sub, name) AS (VALUES
        (1, '5eed0000-0000-4000-8000-000000000001', 'Camille Martin'),
        (2, '5eed0000-0000-4000-8000-000000000002', 'Moussa Diop'),
        (3, '5eed0000-0000-4000-8000-000000000003', 'Léa Fontaine'),
        (4, '5eed0000-0000-4000-8000-000000000004', 'Hugo Tremblay'),
        (5, '5eed0000-0000-4000-8000-000000000005', 'Inès Benali')
    ),
    feedback(id, transaction_id, author, subject, rating, comment, created_days_ago) AS (VALUES
        (1, 4, 4, 1, 5,
         'Super échange, Camille était ponctuelle et le colis est arrivé nickel.', 19),
        (2, 4, 1, 4, 5,
         'Colis bien emballé, communication facile. Je recommande Hugo.', 18),
        (3, 9, 5, 2, 4,
         'Très sérieux, un petit retard à la remise mais prévenu à l''avance.', 23),
        (4, 10, 3, 2, 5,
         'Parfait du début à la fin, merci Moussa !', 22),
        (5, 10, 2, 3, 5,
         'Rien à redire, merci Léa.', 22),
        (6, 14, 4, 3, 4,
         'Bon contact, la remise à l''aéroport était un peu compliquée mais tout s''est bien passé.', 8),
        (7, 14, 3, 4, 5,
         'Très arrangeant sur l''horaire de remise.', 8),
        (8, 17, 4, 2, 3,
         'Le colis dépassait un peu le poids annoncé, sinon ok.', 3)
    )
    INSERT INTO review (id, transaction_id, reviewer_id, reviewer_name, reviewee_id, reviewee_name,
                        rating, comment, created_at)
    SELECT f.id, f.transaction_id, a.sub, a.name, s.sub, s.name, f.rating, f.comment,
           now() - make_interval(days => f.created_days_ago)
    FROM feedback f
    JOIN person a ON a.n = f.author
    JOIN person s ON s.n = f.subject;

    PERFORM setval(pg_get_serial_sequence('review', 'id'), (SELECT max(id) FROM review));
END $$;
