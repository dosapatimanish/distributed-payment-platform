package com.paymentplatform.wallet.service;

import com.paymentplatform.wallet.domain.Currency;
import com.paymentplatform.wallet.exception.CurrencyNotFoundException;
import com.paymentplatform.wallet.exception.UnsupportedCurrencyException;
import com.paymentplatform.wallet.repository.CurrencyRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CurrencyService {

    private final CurrencyRepository currencyRepository;

    public CurrencyService(CurrencyRepository currencyRepository) {
        this.currencyRepository = currencyRepository;
    }

    public List<Currency> listActive() {
        return currencyRepository.findByActive("Y");
    }

    public Currency get(String code) {
        return currencyRepository.findById(code)
                .orElseThrow(() -> new CurrencyNotFoundException(code));
    }

    /** Returns the active currency, or throws {@link UnsupportedCurrencyException} - used when creating a wallet. */
    public Currency requireActive(String code) {
        Currency currency = currencyRepository.findById(code)
                .orElseThrow(() -> new UnsupportedCurrencyException(code));
        if (!currency.isActive()) {
            throw new UnsupportedCurrencyException(code);
        }
        return currency;
    }
}
