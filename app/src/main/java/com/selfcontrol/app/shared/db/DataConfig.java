package com.selfcontrol.app.shared.db;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class DataConfig {

    /** Substitui o JdbcTransactionManager do Spring Boot. Usado por Spring Data JDBC, jOOQ e JdbcClient. */
    @Bean
    @Primary
    RlsTransactionManager transactionManager(DataSource dataSource,
                                             ObjectProvider<TransactionManagerCustomizers> customizers) {
        RlsTransactionManager manager = new RlsTransactionManager(dataSource);
        customizers.ifAvailable(c -> c.customize(manager));
        return manager;
    }

    /**
     * O Spring Session usa um gerenciador próprio, sem RLS: as tabelas de sessão não têm política,
     * e carregar a sessão não pode depender do usuário que ainda está sendo lido dela.
     */
    @Bean
    @SpringSessionTransactionManager
    JdbcTransactionManager sessionTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
