-- Reglement d'une transaction payee : ce qui est rendu a l'acheteur, garde par la plateforme et
-- verse au voyageur, en unites mineures comme stripe_amount. Les montants et un statut PENDING
-- sont ecrits dans la meme ecriture que la transition vers completed ou cancelled ; l'appel a
-- Stripe suit, et SettlementJob relance ce qui reste en attente.
ALTER TABLE transaction_record ADD COLUMN platform_fee BIGINT;

ALTER TABLE transaction_record ADD COLUMN refund_amount BIGINT;
ALTER TABLE transaction_record ADD COLUMN refund_status VARCHAR(32);
ALTER TABLE transaction_record ADD COLUMN stripe_refund_id VARCHAR(255);
ALTER TABLE transaction_record ADD COLUMN refunded_at TIMESTAMP(6);

ALTER TABLE transaction_record ADD COLUMN payout_amount BIGINT;
ALTER TABLE transaction_record ADD COLUMN payout_status VARCHAR(32);
ALTER TABLE transaction_record ADD COLUMN stripe_transfer_id VARCHAR(255);
ALTER TABLE transaction_record ADD COLUMN paid_out_at TIMESTAMP(6);

-- SettlementJob ne cherche que les reglements en attente : une poignee de lignes parmi toutes.
CREATE INDEX IF NOT EXISTS idx_transaction_settlement_pending ON transaction_record (id)
    WHERE refund_status = 'PENDING' OR payout_status IN ('PENDING', 'AWAITING_ACCOUNT');
