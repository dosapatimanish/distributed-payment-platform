package com.paymentplatform.wallet.repository;

import com.paymentplatform.wallet.domain.WalletReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletReservationRepository extends JpaRepository<WalletReservation, String> {
}
