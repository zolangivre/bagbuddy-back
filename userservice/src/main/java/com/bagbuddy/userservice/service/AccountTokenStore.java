package com.bagbuddy.userservice.service;

import com.bagbuddy.userservice.model.AccountToken;
import com.bagbuddy.userservice.repository.AccountTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and redeems the single-use tokens behind account emails.
 *
 * A token is 256 random bits: guessing one is not a threat, so there is no attempt counter.
 * What needs guarding is the mailbox of the account holder, hence the minimum interval between
 * two emails for the same account and purpose.
 */
@Component
public class AccountTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountTokenRepository repository;

    public AccountTokenStore(AccountTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * A fresh token for {@code sub}, replacing any earlier one of the same purpose: only the
     * latest link works. Empty when a token was already issued less than {@code minInterval}
     * ago, so a flood of requests cannot turn into a flood of emails.
     */
    @Transactional
    public Optional<String> issue(String sub, String email, AccountToken.Purpose purpose,
                                  Duration ttl, Duration minInterval) {
        LocalDateTime now = LocalDateTime.now();
        repository.deleteExpired(now);
        if (repository.existsBySubAndPurposeAndCreatedAtAfter(sub, purpose, now.minus(minInterval))) {
            return Optional.empty();
        }
        repository.deleteAllFor(sub, purpose);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AccountToken token = new AccountToken();
        token.setTokenHash(hash(raw));
        token.setSub(sub);
        token.setEmail(email);
        token.setPurpose(purpose);
        token.setExpiresAt(now.plus(ttl));
        repository.save(token);
        return Optional.of(raw);
    }

    /**
     * The unexpired token matching {@code raw}, row-locked until the caller's transaction ends.
     * Must be called inside a transaction, which {@link #redeem} then completes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AccountToken> findUsable(String raw, AccountToken.Purpose purpose) {
        return repository.findForUpdate(hash(raw), purpose)
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    /** Burns the token and every other one of the same account and purpose. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void redeem(AccountToken token) {
        repository.deleteAllFor(token.getSub(), token.getPurpose());
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
