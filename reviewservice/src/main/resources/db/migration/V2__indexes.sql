-- reviewsByReviewee et averageRating, les deux lectures les plus frequentes.
CREATE INDEX IF NOT EXISTS idx_review_reviewee_id ON review (reviewee_id);
CREATE INDEX IF NOT EXISTS idx_review_reviewer_id ON review (reviewer_id);
CREATE INDEX IF NOT EXISTS idx_review_transaction_id ON review (transaction_id);

-- existsByReviewerIdAndTransactionId : garde-fou du "un seul avis par affaire",
-- verifie a chaque ecriture.
CREATE INDEX IF NOT EXISTS idx_review_reviewer_transaction
    ON review (reviewer_id, transaction_id);
