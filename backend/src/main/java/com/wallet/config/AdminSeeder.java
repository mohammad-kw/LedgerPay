package com.wallet.config;

import com.wallet.entity.Role;
import com.wallet.entity.User;
import com.wallet.entity.Wallet;
import com.wallet.repository.UserRepository;
import com.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Creates the single bootstrap ADMIN account on startup, if one doesn't
 * already exist.
 *
 * ── WHY A SEEDER (the security point to defend in interviews) ─────────────
 * There is deliberately NO "register as admin" endpoint: letting anyone tick
 * an "I'm an admin" box during self-registration would be a glaring privilege-
 * escalation hole. Instead, the very first admin has to come into existence
 * through a trusted, out-of-band channel that an attacker on the public API
 * can't reach. Seeding from ENVIRONMENT VARIABLES at deploy time is exactly
 * that: the credentials live in the server's config (never in the codebase,
 * never in a request body), following the same ${ENV_VAR} secret discipline
 * as everything else in this project.
 *
 * Behaviour:
 *   - Runs once, after the Spring context is ready (CommandLineRunner).
 *   - If ADMIN_EMAIL / ADMIN_PASSWORD are blank, it does nothing (so a fresh
 *     local checkout with no admin configured simply has no admin - safe
 *     default).
 *   - If an account with ADMIN_EMAIL already exists, it does nothing (idempotent
 *     - restarting the app won't duplicate or overwrite it).
 *   - Otherwise it creates a User with role=ADMIN plus the usual auto-created
 *     wallet (so the admin is also a fully valid normal account).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.name:Administrator}")
    private String adminName;

    @Override
    @Transactional
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.info("AdminSeeder: no ADMIN_EMAIL/ADMIN_PASSWORD configured - skipping admin creation");
            return;
        }

        if (userRepository.existsByEmail(adminEmail)) {
            log.info("AdminSeeder: admin account '{}' already exists - nothing to do", adminEmail);
            return;
        }

        User admin = User.builder()
                .name(adminName)
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .build();
        admin = userRepository.save(admin);

        // An admin is still a full user, so give them a wallet too, exactly
        // like AuthService.register does for ordinary users.
        Wallet wallet = Wallet.builder()
                .user(admin)
                .balance(BigDecimal.ZERO)
                .build();
        walletRepository.save(wallet);

        log.info("AdminSeeder: created ADMIN account id={} email={}", admin.getId(), admin.getEmail());
    }
}
