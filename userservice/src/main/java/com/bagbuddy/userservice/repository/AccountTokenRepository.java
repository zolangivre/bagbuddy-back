package com.bagbuddy.userservice.repository;

import com.bagbuddy.userservice.model.AccountToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    /** Locked: two submissions of the same link must not both go through. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountToken t where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<AccountToken> findForUpdate(String hash, AccountToken.Purpose purpose);

    boolean existsBySubAndPurposeAndCreatedAtAfter(String sub, AccountToken.Purpose purpose,
                                                   LocalDateTime after);

    @Modifying
    @Query("delete from AccountToken t where t.sub = :sub and t.purpose = :purpose")
    void deleteAllFor(String sub, AccountToken.Purpose purpose);

    @Modifying
    @Query("delete from AccountToken t where t.expiresAt < :now")
    void deleteExpired(LocalDateTime now);
}
