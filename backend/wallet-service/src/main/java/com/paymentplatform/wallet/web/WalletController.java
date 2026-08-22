package com.paymentplatform.wallet.web;

import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletReservation;
import com.paymentplatform.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(request.userId(), request.currency(), request.highContention());
        return ResponseEntity
                .created(URI.create("/api/v1/wallets/" + wallet.getWalletId()))
                .body(WalletResponse.from(wallet));
    }

    @GetMapping("/{walletId}/balance")
    public BalanceResponse getBalance(@PathVariable String walletId) {
        return BalanceResponse.from(walletService.getBalance(walletId));
    }

    @PostMapping("/{walletId}/debit")
    public WalletResponse debit(@PathVariable String walletId, @Valid @RequestBody DebitRequest request) {
        Wallet wallet = walletService.debit(walletId, request.amount(), request.transactionId());
        return WalletResponse.from(wallet);
    }

    @PostMapping("/{walletId}/credit")
    public WalletResponse credit(@PathVariable String walletId, @Valid @RequestBody CreditRequest request) {
        Wallet wallet = walletService.credit(walletId, request.amount(), request.transactionId());
        return WalletResponse.from(wallet);
    }

    @PostMapping("/{walletId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable String walletId, @Valid @RequestBody ReserveRequest request) {
        WalletReservation reservation = walletService.reserveFunds(walletId, request.amount(), request.transactionId());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ReservationResponse.from(reservation));
    }

    @PostMapping("/reservations/{reservationId}/capture")
    public WalletResponse captureReservation(@PathVariable String reservationId) {
        Wallet wallet = walletService.captureReservation(reservationId);
        return WalletResponse.from(wallet);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public WalletResponse releaseReservation(@PathVariable String reservationId) {
        Wallet wallet = walletService.releaseReservation(reservationId);
        return WalletResponse.from(wallet);
    }
}
