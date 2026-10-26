package com.selfcontrol.app.config;

import com.selfcontrol.app.api.GoogleIdTokens;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** API pública: documentação OpenAPI e, se configurado, login de apps nativos com Google. */
@Configuration(proxyBeanMethods = false)
class ApiConfig {

    static final String GOOGLE_CERTS = "https://www.googleapis.com/oauth2/v3/certs";

    @Bean
    OpenAPI openApi(@Value("${app.version:dev}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Self Control Financial API")
                        .version("v1 (" + version + ")")
                        .description("""
                                API pública do Self Control Financial: lançamentos, categorias, relatórios e caixa de \
                                entrada da sua conta.

                                **Autenticação:** crie uma chave em Configurações → Chaves de API e envie \
                                `Authorization: Bearer scfk_...`. Chaves somente leitura só fazem GET.

                                **Limites:** 100 requisições por minuto por usuário (headers `X-RateLimit-Limit` e \
                                `X-RateLimit-Remaining`; acima disso, 429 com `Retry-After`).

                                **Valores:** `amount` é texto decimal ("12.34") e `amountCents` é inteiro em centavos. \
                                Erros seguem a RFC 9457 (application/problem+json).""")
                        .license(new License().name("Uso mediante os Termos de Uso")))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("scfk_... (chave de API) ou ID token do Google")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }

    /** Liga o login com Google na API (apps nativos) quando app.api.google-client-ids tem os client ids. */
    @Bean
    @ConditionalOnExpression("'${app.api.google-client-ids:}' != ''")
    GoogleIdTokens googleIdTokens(@Value("${app.api.google-client-ids}") String clientIds, JdbcClient jdbc) {
        Set<String> audiences = Arrays.stream(clientIds.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_CERTS).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                jwt -> GoogleIdTokens.validate(jwt, audiences)));
        return new GoogleIdTokens(decoder, jdbc);
    }
}
