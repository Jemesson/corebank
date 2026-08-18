package com.corebank.shared.cache;

import com.corebank.query.dto.BalanceDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class BalanceCached {
    private static final String KEY_PREFIX = "account_balance:";
    private static final Logger log = LoggerFactory.getLogger(BalanceCached.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public BalanceCached(StringRedisTemplate redis,
                        ObjectMapper objectMapper,
                        @Value("${corebank.cache.balance-ttl}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    private String key(Long accountId) {
        return KEY_PREFIX + accountId;
    }

    public Optional<BalanceDTO> get(Long accountId) {
        try {
            String json = redis.opsForValue().get(key(accountId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, BalanceDTO.class));
        } catch (Exception e) {
            // Degrada para miss: a leitura segue para o banco.
            log.warn("Falha ao ler o cache de saldo da conta {}, seguindo para o banco: {}",
                    accountId, e.toString());
            return Optional.empty();
        }
    }

    public void putIfNewer(BalanceDTO balance) {
        Optional<BalanceDTO> current = get(balance.accountId());
        
        if (current.isPresent() && current.get().version() != null
                && balance.version() != null
                && current.get().version() > balance.version()) {
            log.debug("Evento fora de ordem para a conta {} (cache v{} > evento v{}), ignorado",
                    balance.accountId(), current.get().version(), balance.version());
            return;
        }

        put(balance);
    }    

    public void evict(Long accountId) {
        try {
            redis.delete(key(accountId));
        } catch (Exception e) {
            log.warn("Falha ao invalidar o cache de saldo da conta {}: {}", accountId, e.toString());
        }
    }

    public void put(BalanceDTO balance) {
        try {
            redis.opsForValue().set(key(balance.accountId()),
                    objectMapper.writeValueAsString(balance), ttl);
        } catch (Exception e) {
            log.warn("Falha ao gravar o cache de saldo da conta {}: {}",
                    balance.accountId(), e.toString());
        }
    }

}
