package com.paymentplatform.wallet.repository;

import com.paymentplatform.wallet.domain.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CurrencyRepository extends JpaRepository<Currency, String> {

    List<Currency> findByActive(String active);
}
