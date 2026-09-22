-- Code de remise : genere au paiement, montre a l'acheteur seul, saisi par le voyageur a la
-- livraison pour clore la transaction. Les essais sont comptes pour bloquer une recherche
-- du code par essais successifs.
ALTER TABLE transaction_record ADD COLUMN handover_code VARCHAR(6);
ALTER TABLE transaction_record ADD COLUMN handover_attempts INTEGER NOT NULL DEFAULT 0;

-- Transactions deja payees : elles recoivent un code, sans quoi le voyageur ne pourrait pas
-- les clore par ce chemin.
UPDATE transaction_record
SET handover_code = lpad(floor(random() * 1000000)::int::text, 6, '0')
WHERE seller_status = 'confirmed' AND buyer_status = 'confirmed' AND handover_code IS NULL;
