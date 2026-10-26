package com.selfcontrol.app.config;

import com.selfcontrol.app.auth.AppOidcUserService;
import com.selfcontrol.app.webhook.WebhookController;
import com.selfcontrol.app.webhook.WebhookTokenFilter;
import com.selfcontrol.app.webhook.WebhookTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.LinkedHashMap;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/login", "/error",
            "/css/**", "/js/**", "/icons/**", "/webjars/**",
            "/manifest.webmanifest", "/sw.js",
            "/actuator/health/**"
    };

    /**
     * Webhook dos Atalhos do iOS: sem sessão e sem CSRF (não há navegador nem cookie), autenticado
     * só pelo token secreto. Vem antes da cadeia principal.
     */
    @Bean
    @Order(1)
    SecurityFilterChain webhookFilterChain(HttpSecurity http, WebhookTokenService tokens) throws Exception {
        http
                .securityMatcher(WebhookController.PATH)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(new WebhookTokenFilter(tokens), AuthorizationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> oauthClients,
                                            AppOidcUserService oidcUserService) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                // A API responde 401; as telas redirecionam para o login.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint()))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .rememberMe(remember -> remember.disable());

        // O login com Google só é ligado quando o perfil "google" traz as credenciais.
        if (oauthClients.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .defaultSuccessUrl("/", true));
        }
        return http.build();
    }

    private static AuthenticationEntryPoint entryPoint() {
        var entryPoints = new LinkedHashMap<RequestMatcher, AuthenticationEntryPoint>();
        entryPoints.put(PathPatternRequestMatcher.pathPattern("/api/**"), new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        var delegating = new DelegatingAuthenticationEntryPoint(entryPoints);
        delegating.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"));
        return delegating;
    }

    /** Argon2id com os parâmetros mínimos recomendados pela OWASP (19 MiB, 2 iterações, 1 thread). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
