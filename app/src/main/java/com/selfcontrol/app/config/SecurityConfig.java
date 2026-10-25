package com.selfcontrol.app.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/login", "/error",
            "/css/**", "/js/**", "/icons/**", "/webjars/**",
            "/manifest.webmanifest", "/sw.js",
            "/actuator/health/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> oauthClients) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .rememberMe(remember -> remember.disable());

        // O login com Google só é ligado quando o perfil "google" traz as credenciais.
        if (oauthClients.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .defaultSuccessUrl("/", true));
        }
        return http.build();
    }

    /** Argon2id com os parâmetros mínimos recomendados pela OWASP (19 MiB, 2 iterações, 1 thread). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
