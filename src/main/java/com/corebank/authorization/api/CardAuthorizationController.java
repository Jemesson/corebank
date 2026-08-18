package com.corebank.authorization.api;

import com.corebank.authorization.dto.CardAuthorizationRequest;
import com.corebank.authorization.dto.CardAuthorizationResponse;
import com.corebank.authorization.service.CardAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/card")
public class CardAuthorizationController {

    private final CardAuthorizationService service;

    public CardAuthorizationController(CardAuthorizationService service) {
        this.service = service;
    }

    @PostMapping("/authorization")
    public ResponseEntity<CardAuthorizationResponse> authorize(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CardAuthorizationRequest request) {

        return ResponseEntity.ok(service.authorize(
                idempotencyKey, request.accountId(), request.amount(), request.merchant()));
    }

    @PostMapping("/authorization/{authorizationCode}/capture")
    public ResponseEntity<CardAuthorizationResponse> capture(@PathVariable String authorizationCode) {
        return ResponseEntity.ok(service.capture(authorizationCode));
    }

    @PostMapping("/authorization/{authorizationCode}/reversal")
    public ResponseEntity<CardAuthorizationResponse> reverse(@PathVariable String authorizationCode) {
        return ResponseEntity.ok(service.reverse(authorizationCode));
    }
}
