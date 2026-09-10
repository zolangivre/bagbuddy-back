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
    IF EXISTS (SELECT 1 FROM users) THEN
        RETURN;
    END IF;

    -- testuser n'a volontairement pas de profil : c'est le compte vierge, cree au
    -- premier appel a 'me' comme pour un nouvel inscrit.
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
    )
    INSERT INTO users (id, sub, email, email_verified, given_name, family_name, name, username,
                       bio, location, phone, created_at, updated_at)
    SELECT p.n, p.sub, p.email, true, p.given_name, p.family_name, p.given_name || ' ' || p.family_name, p.email,
           p.bio, p.location, p.phone, now() - interval '60 days', now() - interval '60 days'
    FROM person p;

    PERFORM setval(pg_get_serial_sequence('users', 'id'), (SELECT max(id) FROM users));
END $$;
