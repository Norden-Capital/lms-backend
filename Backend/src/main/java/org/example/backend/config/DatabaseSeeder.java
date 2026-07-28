package org.example.backend.config;

import org.example.backend.entity.User;
import org.example.backend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration
public class DatabaseSeeder {

    @Bean
    public CommandLineRunner initDatabase(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (userRepository.count() == 0) {
                User admin = User.builder()
                        .firstName("Admin")
                        .lastName("User")
                        .phone("+1112223333")
                        .email("admin@example.com")
                        .password(passwordEncoder.encode("admin123"))
                        .mfaEnabled(false)
                        .build();

                User john = User.builder()
                        .firstName("John")
                        .lastName("Doe")
                        .phone("+1987654321")
                        .email("john.doe@example.com")
                        .password(passwordEncoder.encode("password123"))
                        .mfaEnabled(false)
                        .build();

                User jane = User.builder()
                        .firstName("Jane")
                        .lastName("Smith")
                        .phone(null) // optional phone
                        .email("jane.smith@example.com")
                        .password(passwordEncoder.encode("password123"))
                        .mfaEnabled(false)
                        .build();

                userRepository.saveAll(List.of(admin, john, jane));
                System.out.println("🌱 Database successfully seeded with 3 initial users!");
            } else {
                System.out.println("ℹ️ Database already contains data. Skipping database seed.");
            }
        };
    }
}
