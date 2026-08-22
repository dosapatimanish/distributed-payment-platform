package com.paymentplatform.merchantpayment.repository;

import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantPaymentRepository extends JpaRepository<MerchantPayment, String> {
}
