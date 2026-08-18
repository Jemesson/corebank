package com.corebank.command.api;

import com.corebank.command.dto.PixPaymentRequest;
import com.corebank.command.dto.PixPaymentResponse;
import com.corebank.command.service.PixCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pix")
public class PixCommandController {

    private final PixCommandService pixService;

    public PixCommandController(PixCommandService pixCommandService) {
        this.pixService = pixCommandService;
    }

    @PostMapping("/payment")
    public ResponseEntity<PixPaymentResponse> applyPayment(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PixPaymentRequest request) {

        var response = pixService.payment(
                idempotencyKey, request.originAccountId(), request.targetPix(), request.value());

        return ResponseEntity.ok(response);
    }
}
