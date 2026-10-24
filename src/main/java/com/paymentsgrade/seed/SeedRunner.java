package com.paymentsgrade.seed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Run with: SPRING_PROFILES_ACTIVE=seed ./gradlew bootRun --args='--seed.transfers=500000'
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private final SeedService seeder;
    private final int users;
    private final int wallets;
    private final int transfers;

    public SeedRunner(SeedService seeder, @Value("${seed.users:1000}") int users,
            @Value("${seed.wallets:2000}") int wallets, @Value("${seed.transfers:100000}") int transfers) {
        this.seeder = seeder;
        this.users = users;
        this.wallets = wallets;
        this.transfers = transfers;
    }

    @Override
    public void run(ApplicationArguments args) {
        seeder.seed(users, wallets, transfers);
    }
}
