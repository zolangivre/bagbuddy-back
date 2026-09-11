-- Un avis par auteur et par transaction, garanti par la base.
--
-- ReviewService verifiait deja existsByReviewerIdAndTransactionId avant d'inserer, mais deux
-- envois simultanes (double-clic) passaient tous les deux ce controle. Les doublons eventuels
-- sont retires avant de poser la contrainte, en gardant le premier avis ecrit.
DELETE FROM review r
USING review older
WHERE r.reviewer_id = older.reviewer_id
  AND r.transaction_id = older.transaction_id
  AND r.id > older.id;

-- Remplace l'index non unique de V2 sur les memes colonnes, dans le meme ordre : il sert
-- toujours findByReviewerId et existsByReviewerIdAndTransactionId.
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_reviewer_transaction
    ON review (reviewer_id, transaction_id);
DROP INDEX IF EXISTS idx_review_reviewer_transaction;
