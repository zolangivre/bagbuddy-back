-- Toutes les lectures sont cadrees sur une partie de la transaction : ce sont
-- ces deux colonnes qui sont filtrees, systematiquement.
CREATE INDEX IF NOT EXISTS idx_transaction_buyer_id ON transaction_record (buyer_id);
CREATE INDEX IF NOT EXISTS idx_transaction_seller_id ON transaction_record (seller_id);

-- myTransactions : findByBuyerIdOrSellerIdOrderByCreatedAtDesc.
CREATE INDEX IF NOT EXISTS idx_transaction_created_at ON transaction_record (transaction_created_at DESC);

-- Retarification et reservation repartent de l'annonce.
CREATE INDEX IF NOT EXISTS idx_transaction_listing_id ON transaction_record (listing_id);
