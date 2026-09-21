package com.bagbuddy.transactionservice.repository;

import com.bagbuddy.transactionservice.model.SettlementStatus;
import com.bagbuddy.transactionservice.model.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByBuyerId(String buyerId);

    List<Transaction> findBySellerId(String sellerId);

    List<Transaction> findByBuyerIdOrSellerIdOrderByCreatedAtDesc(String buyerId, String sellerId);

    Long countByBuyerIdOrSellerId(String buyerId, String sellerId);

    /** Reglements a relancer. Couverte par idx_transaction_settlement_pending. */
    @Query("select t.id from Transaction t where t.refundStatus = :pending "
            + "or t.payoutStatus = :pending or t.payoutStatus = :awaitingAccount")
    List<Long> findWithPendingSettlement(@Param("pending") SettlementStatus pending,
                                         @Param("awaitingAccount") SettlementStatus awaitingAccount);

    /**
     * Candidates a l'expiration. departureDate est l'instantane texte de l'annonce, au format de
     * LocalDateTime.toString() : entre dates ISO, l'ordre lexical est l'ordre chronologique. Les
     * valeurs trop courtes pour etre une date sont ecartees plutot que comparees.
     */
    @Query("select t.id from Transaction t where t.listingInfo.departureDate < :now "
            + "and length(t.listingInfo.departureDate) >= 16 "
            + "and concat(t.sellerStatus, '/', t.buyerStatus) in :pairs")
    List<Long> findDepartedWithStatus(@Param("now") String now, @Param("pairs") List<String> pairs);

    /**
     * Verrou de ligne pour la phase d'ecriture de update() : les appels distants ayant eu lieu
     * hors transaction, la ligne est relue ici sous verrou et la transition revalidee.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

    /**
     * Somme calculee en SQL. Charger l'historique complet d'un utilisateur pour n'additionner
     * qu'une colonne hydratait chaque ligne avec ses deux UserInfo et son ListingInfo embarques.
     */
    @Query("select coalesce(sum(t.total), 0) from Transaction t where t.sellerId = :userId "
            + "and lower(t.sellerStatus) = 'completed' and lower(t.buyerStatus) = 'completed'")
    BigDecimal sumCompletedBySeller(@Param("userId") String userId);

    @Query("select coalesce(sum(t.total), 0) from Transaction t where t.buyerId = :userId "
            + "and lower(t.sellerStatus) = 'completed' and lower(t.buyerStatus) = 'completed'")
    BigDecimal sumCompletedByBuyer(@Param("userId") String userId);

    /**
     * Les quatre colonnes qui suffisent a savoir qui participe et ou en est la transaction. Le fil
     * de messages est relu a intervalle par le front : hydrater la ligne entiere, ses deux UserInfo
     * et son ListingInfo embarques, pour ne lire que buyerId et sellerId, etait le plus gros
     * gaspillage du chemin le plus frequent.
     */
    @Query("select t.buyerId as buyerId, t.sellerId as sellerId, "
            + "t.sellerStatus as sellerStatus, t.buyerStatus as buyerStatus "
            + "from Transaction t where t.id = :id")
    Optional<Participants> findParticipants(@Param("id") Long id);

    /** Projection de findParticipants. */
    interface Participants {
        String getBuyerId();

        String getSellerId();

        String getSellerStatus();

        String getBuyerStatus();
    }
}
