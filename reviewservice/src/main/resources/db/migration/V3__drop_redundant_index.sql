-- Prefixe strict de idx_review_reviewer_transaction (reviewer_id, transaction_id),
-- que Postgres utilise aussi bien pour findByReviewerId. Deux B-trees pour un besoin.
DROP INDEX IF EXISTS idx_review_reviewer_id;
