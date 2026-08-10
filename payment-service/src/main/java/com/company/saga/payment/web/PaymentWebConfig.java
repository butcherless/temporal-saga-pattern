package com.company.saga.payment.web;

import com.company.saga.payment.persistence.PaymentRepository;
import com.company.saga.payment.service.PaymentProgressionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires {@link PaymentProgressionService} for {@link PaymentController}. */
@Configuration
public class PaymentWebConfig {

    @Bean
    public PaymentProgressionService paymentProgressionService(final PaymentRepository paymentRepository) {
        return new PaymentProgressionService(paymentRepository);
    }
}
