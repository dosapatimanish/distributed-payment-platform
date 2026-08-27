package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletReservation;
import com.paymentplatform.wallet.idempotency.IdempotencyGuard;
import com.paymentplatform.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Every write endpoint requires an {@code Idempotency-Key} header (design doc §6.2.3) and routes
 * the mutation through {@link IdempotencyGuard#runIdempotent}. {@code getBalance} is read-only.
 * Path variable {@code accountNo} is the 12-char bank-style account number.
 */
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;
    private final IdempotencyGuard idempotencyGuard;

    public WalletController(WalletService walletService, IdempotencyGuard idempotencyGuard) {
        this.walletService = walletService;
        this.idempotencyGuard = idempotencyGuard;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = idempotencyGuard.runIdempotent(idempotencyKey, WalletResponse.class, () -> {
            Wallet wallet = walletService.createWallet(request.cif(), request.currency(), request.highContention());
            return WalletResponse.from(wallet);
        });
        return ResponseEntity
                .created(URI.create("/api/v1/wallets/" + response.accountNo()))
                .body(response);
    }

    @GetMapping("/{accountNo}/balance")
    public BalanceResponse getBalance(@PathVariable String accountNo) {
        return BalanceResponse.from(walletService.getBalance(accountNo));
    }

    @PostMapping("/{accountNo}/debit")
    public WalletResponse debit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @PathVariable String accountNo, @Valid @RequestBody DebitRequest request) {
        return idempotencyGuard.runIdempotent(idempotencyKey, WalletResponse.class, () -> {
            Wallet wallet = walletService.debit(accountNo, request.amount(), request.transactionId());
            return WalletResponse.from(wallet);
        });
    }

    @PostMapping("/{accountNo}/credit")
    public WalletResponse credit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                  @PathVariable String accountNo, @Valid @RequestBody CreditRequest request) {
        return idempotencyGuard.runIdempotent(idempotencyKey, WalletResponse.class, () -> {
            Wallet wallet = walletService.credit(accountNo, request.amount(), request.transactionId());
            return WalletResponse.from(wallet);
        });
    }

    @PostMapping("/{accountNo}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @PathVariable String accountNo, @Valid @RequestBody ReserveRequest request) {
        ReservationResponse response = idempotencyGuard.runIdempotent(idempotencyKey, ReservationResponse.class, () -> {
            WalletReservation reservation = walletService.reserveFunds(accountNo, request.amount(), request.transactionId());
            return ReservationResponse.from(reservation);
        });
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/reservations/{reservationId}/capture")
    public WalletResponse captureReservation(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @PathVariable String reservationId) {
        return idempotencyGuard.runIdempotent(idempotencyKey, WalletResponse.class,
                () -> WalletResponse.from(walletService.captureReservation(reservationId)));
    }

    @PostMapping("/reservations/{reservationId}/release")
    public WalletResponse releaseReservation(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @PathVariable String reservationId) {
        return idempotencyGuard.runIdempotent(idempotencyKey, WalletResponse.class,
                () -> WalletResponse.from(walletService.releaseReservation(reservationId)));
    }
}
