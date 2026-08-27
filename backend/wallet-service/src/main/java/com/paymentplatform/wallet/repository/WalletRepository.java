package com.paymentplatform.wallet.repository;

import com.paymentplatform.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    Optional<Wallet> findByCifAndCurrency(String cif, String currency);

    /**
     * Loads the wallet with a pessimistic write lock (SELECT ... FOR UPDATE), for the
     * high-contention path (design doc §6.2.1). The query hint pins an explicit lock-wait
     * timeout at this call site, on top of the application-wide default in
     * application.properties, so a stalled caller fails fast instead of queuing forever.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select w from Wallet w where w.accountNo = :accountNo")
    Optional<Wallet> findByIdForUpdate(@Param("accountNo") String accountNo);
}
