package com.paymentplatform.wallet.service;

import com.paymentplatform.wallet.domain.Customer;
import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.exception.CifTakenException;
import com.paymentplatform.wallet.exception.EmailAlreadyRegisteredException;
import com.paymentplatform.wallet.exception.InvalidCredentialsException;
import com.paymentplatform.wallet.repository.CustomerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Registration and sign-in against {@code customer_master}. Registration also opens the
 * customer's first wallet, in the same transaction, via {@link WalletService#createWallet}.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final WalletService walletService;
    private final PasswordHasher passwordHasher;

    public CustomerService(CustomerRepository customerRepository,
                            WalletService walletService,
                            PasswordHasher passwordHasher) {
        this.customerRepository = customerRepository;
        this.walletService = walletService;
        this.passwordHasher = passwordHasher;
    }

    public record RegistrationResult(Customer customer, Wallet wallet) {
    }

    @Transactional
    public RegistrationResult register(String cif, String firstName, String lastName, String email,
                                        String rawPassword, String currency, boolean highContention) {
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (customerRepository.existsByEmail(normalisedEmail)) {
            throw new EmailAlreadyRegisteredException(normalisedEmail);
        }

        Customer customer = new Customer(
                cif, firstName.trim(), lastName.trim(), normalisedEmail, passwordHasher.hash(rawPassword));
        try {
            customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException ex) {
            if (customerRepository.existsByEmail(normalisedEmail)) {
                throw new EmailAlreadyRegisteredException(normalisedEmail);
            }
            throw new CifTakenException(cif);
        }

        // Joins this transaction; a currency/duplicate failure here rolls the customer back too.
        Wallet wallet = walletService.createWallet(cif, currency, highContention);
        return new RegistrationResult(customer, wallet);
    }

    @Transactional(readOnly = true)
    public Customer authenticate(String email, String rawPassword) {
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);
        Customer customer = customerRepository.findByEmail(normalisedEmail)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordHasher.matches(rawPassword, customer.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return customer;
    }
}
