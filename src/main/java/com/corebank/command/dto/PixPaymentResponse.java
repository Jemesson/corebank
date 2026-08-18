package com.corebank.command.dto;

public record PixPaymentResponse(String endToEndId, String status) {

    public static PixPaymentResponse completed(String endToEndId) {
        return new PixPaymentResponse(endToEndId, "COMPLETED");
    }

    public static PixPaymentResponse replayed(String endToEndId) {
        return new PixPaymentResponse(endToEndId, "REPLAYED");
    }
}
