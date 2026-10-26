package com.selfcontrol.app.config;

import com.selfcontrol.app.api.ApiAuthenticationFilter;
import com.selfcontrol.app.api.GoogleIdTokens;
import com.selfcontrol.app.api.Scopes;
import com.selfcontrol.app.apikey.ApiKeyService;
import com.selfcontrol.app.auth.AppOidcUserService;
import com.selfcontrol.app.billing.StripeWebhookController;
import com.selfcontrol.app.shared.error.Problems;
import com.selfcontrol.app.shared.ratelimit.RateLimitFilter;
import com.selfcontrol.app.shared.ratelimit.RateLimiter;
import com.selfcontrol.app.shared.security.AppPrincipal;
import com.selfcontrol.app.webhook.WebhookController;
import com.selfcontrol.app.webhook.WebhookTokenFilter;
import com.selfcontrol.app.webhook.WebhookTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.RequestCacheAwareFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quatro cadeias de segurança, da mais específica para a geral:
 * <ol>
 *     <li>webhook dos Atalhos do iOS: token secreto, sem sessão nem CSRF;</li>
 *     <li>webhook do Stripe: público, autenticado pela assinatura dentro do controller;</li>
 *     <li>API pública /api/v1: chave de API ou ID token do Google, sem sessão nem CSRF, com rate limit e CORS;</li>
 *     <li>telas e API da sessão (/api/...): login com senha ou Google, cookie de sessão + CSRF.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    static final String API_V1 = "/api/v1/**";

    private static final String[] PUBLIC_PATHS = {
            "/login", "/register", "/verify-email", "/forgot-password", "/reset-password", "/legal/terms",
            "/legal/privacy", "/error",
            "/css/**", "/js/**", "/icons/**", "/webjars/**",
            "/manifest.webmanifest", "/sw.js",
            "/docs", "/docs/**", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
            "/actuator/health/**"
    };

    /** Formulários que um robô usaria para adivinhar senhas ou criar contas em massa: limite por IP. */
    private static final RequestMatcher AUTH_FORMS = request -> HttpMethod.POST.matches(request.getMethod())
            && List.of("/login", "/register", "/forgot-password", "/reset-password")
            .contains(request.getRequestURI().substring(request.getContextPath().length()));

    /**
     * Content-Security-Policy: só scripts da própria app (sem inline nem eval). Estilos inline são
     * permitidos porque as cores das categorias vão no atributo style. Os destinos de formulário
     * incluem o checkout e o portal do Stripe, para onde o upgrade redireciona.
     */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: https://*.googleusercontent.com",
            "connect-src 'self'",
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self' https://accounts.google.com https://checkout.stripe.com https://billing.stripe.com");

    private final RateLimits limits;

    SecurityConfig(RateLimits limits) {
        this.limits = limits;
    }

    /**
     * Webhook dos Atalhos do iOS: sem sessão e sem CSRF (não há navegador nem cookie), autenticado
     * só pelo token secreto. Vem antes da cadeia principal.
     */
    @Bean
    @Order(1)
    SecurityFilterChain webhookFilterChain(HttpSecurity http, WebhookTokenService tokens) throws Exception {
        stateless(http)
                .securityMatcher(WebhookController.PATH)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new WebhookTokenFilter(tokens), BasicAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(limits.webhook(), SecurityConfig::userKey, false),
                        BasicAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** Webhook do Stripe: público para o Spring Security; o controller confere a assinatura. */
    @Bean
    @Order(2)
    SecurityFilterChain billingWebhookFilterChain(HttpSecurity http) throws Exception {
        stateless(http)
                .securityMatcher(StripeWebhookController.PATH)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * API pública versionada. Nunca aceita o cookie de sessão (sem CSRF, um site malicioso poderia
     * usá-lo): só credenciais no header Authorization. GET exige SCOPE_read; o resto, SCOPE_write.
     */
    @Bean
    @Order(3)
    SecurityFilterChain apiFilterChain(HttpSecurity http, ApiKeyService keys, ObjectProvider<GoogleIdTokens> google,
                                       @Value("${app.api.cors.allowed-origins:}") String allowedOrigins)
            throws Exception {
        ApiAuthenticationFilter authentication = new ApiAuthenticationFilter(keys, google.getIfAvailable());
        stateless(http)
                .securityMatcher(API_V1)
                .cors(cors -> cors.configurationSource(cors(allowedOrigins)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, API_V1).hasAuthority(Scopes.READ.getAuthority())
                        .requestMatchers(HttpMethod.HEAD, API_V1).hasAuthority(Scopes.READ.getAuthority())
                        .anyRequest().hasAuthority(Scopes.WRITE.getAuthority()))
                // Ordem: limite por IP (contra enxurrada de credenciais inválidas) → autenticação → limite por usuário.
                .addFilterBefore(new RateLimitFilter(limits.ip(), HttpServletRequest::getRemoteAddr, false),
                        BasicAuthenticationFilter.class)
                .addFilterAfter(authentication, BasicAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(limits.api(), SecurityConfig::userKey, true),
                        RequestCacheAwareFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            Problems.write(response, HttpStatus.UNAUTHORIZED,
                                    "Envie uma chave de API válida em Authorization: Bearer scfk_...");
                        })
                        .accessDeniedHandler((request, response, e) -> Problems.write(response, HttpStatus.FORBIDDEN,
                                "Esta chave é somente leitura")));
        return http.build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ObjectProvider<ClientRegistrationRepository> oauthClients,
                                            AppOidcUserService oidcUserService) throws Exception {
        var failure = new ExceptionMappingAuthenticationFailureHandler();
        failure.setExceptionMappings(Map.of(DisabledException.class.getName(), "/login?disabled"));
        failure.setDefaultFailureUrl("/login?error");

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureHandler(failure)
                        .permitAll())
                .addFilterBefore(new RateLimitFilter(limits.auth(),
                        request -> AUTH_FORMS.matches(request) ? request.getRemoteAddr() : null, false),
                        UsernamePasswordAuthenticationFilter.class)
                // A API responde 401; as telas redirecionam para o login.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint()))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .rememberMe(remember -> remember.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy("camera=(), microphone=(), geolocation=()")));

        // O login com Google só é ligado quando o perfil "google" traz as credenciais.
        if (oauthClients.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .failureHandler(failure)
                    .defaultSuccessUrl("/", true));
        }
        return http.build();
    }

    /** Cadeias de máquina: sem sessão, sem CSRF, sem guardar a requisição para depois do login. */
    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
    }

    private static String userKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppPrincipal p ? p.userId().toString() : null;
    }

    /** Origens liberadas para chamar a API pelo navegador (apps web de terceiros). Vazio: nenhuma. */
    static CorsConfigurationSource cors(String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::strip).filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Location", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                "X-Request-Id"));
        // Credenciais vão no header Authorization, nunca em cookie.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(API_V1, config);
        return source;
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
