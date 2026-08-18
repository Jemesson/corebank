package com.corebank.authorization.service;

import com.corebank.authorization.dto.AccountSnapshot;
import com.corebank.authorization.dto.CardAuthorization;
import com.corebank.authorization.dto.CardAuthorizationResponse;
import com.corebank.authorization.repository.AuthorizationRepository;
import com.corebank.outbox.BalanceUpdatedEvent;
import com.corebank.outbox.OutboxWriter;
import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.exception.AuthorizationNotFoundException;
import com.corebank.shared.exception.InvalidAuthorizationStateException;
import com.corebank.shared.idempotency.IdempotencyManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class CardAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(CardAuthorizationService.class);
    private static final String ENDPOINT = "/api/card/authorization";

    private static final String APPROVED = "APPROVED";
    private static final String DENIED = "DENIED";
    private static final String CAPTURED = "CAPTURED";
    private static final String REVERSED = "REVERSED";

    private final AuthorizationRepository repository;
    private final OutboxWriter outboxWriter;
    private final IdempotencyManagement idempotency;

    public CardAuthorizationService(AuthorizationRepository repository,
                                    OutboxWriter outboxWriter,
                                    IdempotencyManagement idempotency) {
        this.repository = repository;
        this.outboxWriter = outboxWriter;
        this.idempotency = idempotency;
    }

    @Transactional
    public CardAuthorizationResponse authorize(String idempotencyKey, Long accountId,
                                               BigDecimal amount, String merchant) {

        var replay = idempotency.reserveOrReplay(idempotencyKey, ENDPOINT, accountId);

        if (replay.isPresent()) {
            var previous = repository.lockAuthorization(replay.get())
                    .orElseThrow(() -> new AuthorizationNotFoundException(replay.get()));
            var snapshot = repository.lockAccount(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));

                    return new CardAuthorizationResponse(previous.authorizationCode(), previous.status(),
                    "Authorization replay already decided", snapshot.balance());
        }

        AccountSnapshot account = repository.lockAccount(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        var authorizationCode = generateAuthorizationCode();

        if (account.balance().compareTo(amount) < 0) {
            repository.record(authorizationCode, accountId, amount, merchant, DENIED);
            idempotency.complete(idempotencyKey, ENDPOINT, authorizationCode);

            log.info("Authorization denied: account={} amount={} available={} code={}",
                    accountId, amount, account.balance(), authorizationCode);

            return new CardAuthorizationResponse(authorizationCode, DENIED,
                    "Insufficient available balance", account.balance());
        }

        AccountSnapshot updated = repository.applyHold(accountId, amount);
        repository.record(authorizationCode, accountId, amount, merchant, APPROVED);
        publish(updated);
        idempotency.complete(idempotencyKey, ENDPOINT, authorizationCode);

        log.info("Authorization approved: account={} amount={} code={} availableAfter={}",
                accountId, amount, authorizationCode, updated.balance());

        return new CardAuthorizationResponse(authorizationCode, APPROVED, null, updated.balance());
    }

    @Transactional
    public CardAuthorizationResponse capture(String authorizationCode) {
        var authorization = requireStatus(authorizationCode, APPROVED);

        AccountSnapshot updated = repository.settleHold(authorization.accountId(), authorization.amount());
        repository.updateStatus(authorizationCode, CAPTURED);
        publish(updated);

        log.info("Authorization captured: code={} account={} amount={}",
                authorizationCode, authorization.accountId(), authorization.amount());

        return new CardAuthorizationResponse(authorizationCode, CAPTURED, null, updated.balance());
    }

    @Transactional
    public CardAuthorizationResponse reverse(String authorizationCode) {
        var authorization = requireStatus(authorizationCode, APPROVED);

        AccountSnapshot updated = repository.releaseHold(authorization.accountId(), authorization.amount());
        repository.updateStatus(authorizationCode, REVERSED);
        publish(updated);

        log.info("Authorization reversed: code={} account={} amount={}",
                authorizationCode, authorization.accountId(), authorization.amount());

        return new CardAuthorizationResponse(authorizationCode, REVERSED, null, updated.balance());
    }

    private CardAuthorization requireStatus(String authorizationCode, String expected) {
        var authorization = repository.lockAuthorization(authorizationCode)
                .orElseThrow(() -> new AuthorizationNotFoundException(authorizationCode));

        if (!expected.equals(authorization.status())) {
            throw new InvalidAuthorizationStateException(authorizationCode, authorization.status(), expected);
        }
        return authorization;
    }

    private void publish(AccountSnapshot account) {
        outboxWriter.balanceUpdated(new BalanceUpdatedEvent(
                account.accountId(), account.totalBalance(), account.balance(), account.version()));
    }

    private String generateAuthorizationCode() {
        return "AUT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
