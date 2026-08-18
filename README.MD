# CoreBank

Fintech de conta digital.
Este repositório implementa o desafio proposto de System Design.

## Problemas

- 2 milhoes de contas ativas.
- Toda consulta ia direto ao PostgreSQL primario.
- Banco com CPU em 90%.
- Escritas (PIX, cartao) sao apenas 10% da carga, mas competiam pelos mesmos recursos.
- **Requisito:** o saldo exibido pode ter ate 5 segundos de defasagem apos uma
  transação; a autorização do cartão precisa ser **precisa** (nao pode negar por saldo errado).

## Pre-requisitos

- Java 21+
- Maven 3.8+
- Docker e Docker Compose

## Arquitetura da Aplicação

```mermaid
flowchart TB
    Client(["<b>Cliente</b><br/>2M contas ativas<br/>~90% leitura / ~10% escrita"])

    subgraph CoreBank["Sistema CoreBank"]
        direction TB

        subgraph App["Microsserviços do CoreBank"]
            direction TB
            
            Command["<b>PixCommandController -> PixCommandService</b><br/>POST /api/pix/payment"]

            Query["<b>PixQueryController -> PixQueryService</b><br/>GET /api/pix/:id/balance<br/>GET /api/pix/:id/statement?limit,offset<br/>"]
            
            Auth["<b>CardAuthorizationController -> CardAuthorizationService</b><br/>POST /api/card/authorization, /api/card/capture e /api/card/reversal"]
            
            Relay["<b>OutboxRelay</b><br/>Replicação agendada do Outbox para o Redis<br/>@Scheduled fixedDelay=300ms "]
        end

        Cache[("<b>Redis:6379</b><br/>Cache do Saldo com TTL 5s<br/>")]
       
        Primary[("<b>PostgreSQL 16 Primario</b> :5432<br/>accounts, pix_transactions, card_authorizations,<br/>idempotency_keys, outbox")]
       
        Replica[("<b>PostgreSQL 16 Replica</b> :5433 <br/>")]
    end

    Client -->|"GET Saldo e Extrato - ~90% do trafego"| Query
    Client ==>|"POST PIX - ~10% do trafego"| Command
    Client ==>|"POST Autorizacao de Cartão"| Auth

    Query -->|"GET account_balance:id - Cache hit"| Cache
    Query -->|"Consulta na Réplica - Cache miss"| Replica

    Command ==>|"Persistir no Banco primário"| Primary
    Auth ==>|"Utiliza a Primary para Autorização do cartão precisa ser precisa "| Primary

    Relay -->|"2PhaseCommit para persistir na base primária e no Outbox (Atualização da Cache)"| Primary
    Relay ==>|"Atualizar o Cache com o Saldo do AccountId com TTL 5s"| Cache

    Primary -.->|"Replicação para atualizar a Réplica"| Replica
    Cache -.->|"Cache miss ou Redis indisponivel:<br/>Consulta do saldo bate na Réplica"| Replica

    classDef person fill:#08427B,stroke:#052E56,color:#FFFFFF
    classDef container fill:#438DD5,stroke:#2E6295,color:#FFFFFF
    classDef store fill:#2E7D5B,stroke:#1B5138,color:#FFFFFF
    class Client person
    class Query,Command,Auth,Relay container
    class Cache,Primary,Replica store
```

### Componentes - Controller/Service/Repository

```mermaid
flowchart TB
    subgraph API["API - Controllers"]
        QC[PixQueryController]
        CC[PixCommandController]
        AC[CardAuthorizationController]
        GEH[GlobalExceptionHandler]
    end

    subgraph SERVICE["Service"]
        QS[PixQueryService]
        CS[PixCommandService]
        AS[CardAuthorizationService]
    end

    subgraph REPO["Repository"]
        AQR[AccountQueryRepository]
        PQR[PixQueryRepository]
        AWR[AccountWriteRepository]
        PWR[PixTransactionWriteRepository]
        AR[AuthorizationRepository]
    end

    subgraph DATA["Infraestrutura"]
        Redis[(Redis)]
        Rep[(ReplicaDB)]
        Pri[(PrimaryDB)]
    end

    subgraph SHARED["Cache e Idempotency/Outbox"]
        IDEM[IdempotencyManagement]
        BC[BalanceCached]
        OW[OutboxWriter]
        RL[OutboxRelay]
    end

    QC --> QS
    CC --> CS
    AC --> AS

    QS --> BC & AQR & PQR
    CS --> IDEM & AWR & PWR & OW
    AS --> IDEM & AR & OW

    BC --> Redis
    RL --> BC
    AQR & PQR --> Rep
    AWR & PWR & AR & OW & IDEM & RL --> Pri
```

## Estrutura do código

```
src/main/java/com/corebank/
├── command/            escrita de PIX (primario)
├── query/              leitura de saldo e extrato (Redis -> replica)
├── authorization/      autorizacao de cartao (primario, sob lock)
├── outbox/             evento transacional e atualiza o cache a cada x tempo.
├── shared/
│   ├── cache/          Classe de Cache - BalanceCached
│   ├── idempotency/    Classe para lidar com a Idempotência
│   ├── exception/      Exceções de negócio
│   └── api/            HTTP error handler.
└── config/             Configs dos datasources primário e sua réplica
```

## Setup

```bash
docker compose up -d
```

Subindo em três containers:

| Container | Porta |
|---|---|
| `corebank-postgres-primary` | 5432 |
| `corebank-postgres-replica` | 5433 |
| `corebank-redis` | 6379 |


```bash
docker exec corebank-postgres-primary psql -U postgres -d corebank \
  -c "SELECT application_name, state, sync_state, replay_lag FROM pg_stat_replication;"

docker exec corebank-postgres-replica psql -U postgres -d corebank \
  -c "SELECT pg_is_in_recovery();"
```

## Rodando a aplicação
App disponível na porta 8080.
```bash
mvn spring-boot:run
```

## Testes

Os testes unitários usam mocks: não precisam dos containers do Postgres ou Redis no ar.

```bash
mvn test
```

```bash
mvn -Dtest=AccountTest test                                     
mvn -Dtest='AccountTest*#debitsAvailableAndTotalBalance' test
```

### Cobertura de código

O JaCoCo utiliza o `mvn verify`. Portanto para gerar o report:

```bash
mvn verify
```

O HTML fica no `target/site/jacoco/index.html`:

```bash
open target/site/jacoco/index.html
```
![alt text](code-coverage.png)

## Endpoints

### Escrita - PIX

```bash
curl -X POST http://localhost:8080/api/pix/payment \
  -H 'Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000' \
  -H 'Content-Type: application/json' \
  -d '{"originAccountId":1001,"targetPix":"joao@banco.com","value":250.00}'
```

```json
{ "endToEndId": "E1787029754049...", "status": "COMPLETED" }
```

Repetir a chamada com a mesma `Idempotency-Key` devolve o mesmo `endToEndId` com
status `REPLAYED`, sem debitar de novo.

### Consulta o Saldo e Extrato

```bash
curl http://localhost:8080/api/pix/1001/balance
curl 'http://localhost:8080/api/pix/1001/statement?limit=20&offset=0'
```

### Autorização de Cartão:

```bash
curl -X POST http://localhost:8080/api/card/authorization \
  -H 'Idempotency-Key: 7c9e6679-7425-40de-944b-e07fc1f90ae7' \
  -H 'Content-Type: application/json' \
  -d '{"accountId":1001,"amount":80.00,"merchant":"Restaurante"}'

curl -X POST http://localhost:8080/api/card/authorization/{authorizationCode}/capture

curl -X POST http://localhost:8080/api/card/authorization/{authorizationCode}/reversal
```

```json
{ "authorizationCode": "AUT4581B911A3AF43EA", "status": "APPROVED",
  "reason": null, "availableBalance": 20.00 }
```

## Codigos de resposta HTTP

| Situacao | HTTP | Codigo |
|---|---|---|
| Valor inválido, campo faltando, header ausente | 400 | `VALIDATION_ERROR`, `MISSING_HEADER` |
| Conta ou autorização inexistente | 404 | `NOT_FOUND` |
| Chave de idempotência em processamento | 409 | `REQUEST_IN_PROGRESS` |
| Autorização já capturada ou estornada | 409 | `INVALID_AUTHORIZATION_STATE` |
| Saldo insuficiente para PIX | 422 | `INSUFFICIENT_BALANCE` |
| Banco indisponível (retentavel) | 503 | `DATASTORE_UNAVAILABLE` |

## Configuração de TTL e Relay pro Redis

| Propriedade | Padrão | Descrição |
|---|---|---|
| `corebank.cache.balance-ttl` | `5s` | TTL do saldo. |
| `corebank.outbox.poll-interval` | `300` | Intervalo de varredura do relay, em ms. |
| `corebank.outbox.batch-size` | `200` | Eventos por ciclo. |
| `spring.datasource.primary.hikari.maximum-pool-size` | `20` | Pool do primario. |
| `spring.datasource.replica.hikari.maximum-pool-size` | `40` | Pool da replica - maior, e onde mora a carga. |

## Edge cases - Cenários do Banco e Redis

**A leitura realmente usa a Réplica?** 
Derrubando a réplica: Saldo e Extrato. Resposta com HTTP 503.
Transações de PIX e Autorização de cartão funcionam somente no banco primário.

```bash
docker stop corebank-postgres-replica
docker exec corebank-redis redis-cli DEL account_balance:1001
curl -i http://localhost:8080/api/pix/1001/balance          # 503
curl -X POST http://localhost:8080/api/card/authorization \
  -H 'Idempotency-Key: teste-1' -H 'Content-Type: application/json' \
  -d '{"accountId":1001,"amount":10.00,"merchant":"Teste"}' # 200
docker start corebank-postgres-replica
```

**Parando o Redis:**

```bash
docker stop corebank-redis
curl http://localhost:8080/api/pix/1001/balance 
docker start corebank-redis
```
# END
