package com.corebank.command.service;

import com.corebank.command.dto.PixPaymentResponse;
import com.corebank.command.entity.Account;
import com.corebank.command.entity.PixTransaction;
import com.corebank.command.repository.AccountWriteRepository;
import com.corebank.command.repository.PixTransactionWriteRepository;
import com.corebank.outbox.BalanceUpdatedEvent;
import com.corebank.outbox.OutboxWriter;
import com.corebank.shared.exception.AccountNotFoundException;
import com.corebank.shared.idempotency.IdempotencyManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Escrita de PIX. Roda inteira no primario, numa unica transacao:
 *
 *   1. reserva a chave de idempotencia
 *   2. trava a conta (SELECT ... FOR UPDATE)
 *   3. debita disponivel e total (PIX liquida na hora)
 *   4. registra a transacao
 *   5. grava o evento no outbox
 *   6. guarda a resposta na chave de idempotencia
 *
 * Tudo ou nada.
 */
@Service
public class PixCommandService {

    private static final Logger log = LoggerFactory.getLogger(PixCommandService.class);
    private static final String ENDPOINT = "/api/pix/payment";

    private final AccountWriteRepository accountRepository;
    private final PixTransactionWriteRepository pixRepository;
    private final OutboxWriter outboxWriter;
    private final IdempotencyManagement idempotency;

    public PixCommandService(AccountWriteRepository accountRepository,
                             PixTransactionWriteRepository pixRepository,
                             OutboxWriter outboxWriter,
                             IdempotencyManagement idempotency) {
        this.accountRepository = accountRepository;
        this.pixRepository = pixRepository;
        this.outboxWriter = outboxWriter;
        this.idempotency = idempotency;
    }

    @Transactional
    public PixPaymentResponse payment(String idempotencyKey, Long originAccountId,
                                      String targetPix, BigDecimal value) {

        var replay = idempotency.reserveOrReplay(idempotencyKey, ENDPOINT, originAccountId);
        if (replay.isPresent()) {
            return PixPaymentResponse.replayed(replay.get());
        }

        Account account = accountRepository.findByIdForUpdate(originAccountId)
                .orElseThrow(() -> new AccountNotFoundException(originAccountId));

        account.withdraw(value);
        Account saved = accountRepository.save(account);

        var endToEndId = generateEndToEndId();
        pixRepository.save(new PixTransaction(endToEndId, originAccountId, targetPix, value));

        outboxWriter.balanceUpdated(new BalanceUpdatedEvent(
                saved.getId(), saved.getTotalBalance(), saved.getBalance(), saved.getVersion()));

        idempotency.complete(idempotencyKey, ENDPOINT, endToEndId);

        log.info("PIX enviado: conta={} valor={} endToEndId={} saldoDisponivel={}",
                originAccountId, value, endToEndId, saved.getBalance());

        return PixPaymentResponse.completed(endToEndId);
    }

    private String generateEndToEndId() {
        return "E" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8);
    }
}
