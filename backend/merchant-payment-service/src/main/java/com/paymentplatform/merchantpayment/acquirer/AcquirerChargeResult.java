package com.paymentplatform.merchantpayment.acquirer;

/** Outcome of one {@link AcquirerGatewayClient#charge} call - exactly one of the two fields is populated. */
public record AcquirerChargeResult(boolean approved, String acquirerRef, String declineReason) {

    public static AcquirerChargeResult approved(String acquirerRef) {
        return new AcquirerChargeResult(true, acquirerRef, null);
    }

    public static AcquirerChargeResult declined(String reason) {
        return new AcquirerChargeResult(false, null, reason);
    }
}
