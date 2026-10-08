package com.paytmmoney.seatreservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit transactions instead of {@code @Transactional} (ADR-020): no proxy self-invocation pitfall, the retry loop
 * can sit outside the transaction, and the code shows exactly where each transaction begins and ends.
 */
@Configuration(proxyBeanMethods = false)
public class TransactionConfig {

    /**
     * READ COMMITTED + explicit row locks and conditional writes (ADR-005). SERIALIZABLE would turn every hot-seat
     * loser into a 40001 abort and retry avalanche.
     */
    @Bean
    TransactionTemplate readCommittedTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
