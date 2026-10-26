package com.selfcontrol.app.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/** Filtros de borda (HTTPS e log de acesso), interceptadores e o relógio da app. */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private final HandlerInterceptor termsInterceptor;

    WebConfig(@org.springframework.beans.factory.annotation.Qualifier("termsInterceptor") HandlerInterceptor termsInterceptor) {
        this.termsInterceptor = termsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Só as telas: API, webhooks, estáticos e as próprias páginas legais ficam de fora.
        registry.addInterceptor(termsInterceptor)
                .excludePathPatterns("/api/**", "/legal/**", "/logout", "/error", "/css/**", "/js/**", "/icons/**",
                        "/webjars/**", "/docs/**", "/swagger-ui/**", "/v3/api-docs/**", "/actuator/**",
                        "/settings/profile/avatar");
    }

    /** Depois do ForwardedHeaderFilter (que resolve X-Forwarded-Proto) e antes de todo o resto. */
    @Bean
    FilterRegistrationBean<RequireHttpsFilter> requireHttpsFilter(@Value("${app.security.require-https:false}") boolean enabled) {
        FilterRegistrationBean<RequireHttpsFilter> registration = new FilterRegistrationBean<>(new RequireHttpsFilter(enabled));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
        FilterRegistrationBean<RequestLogFilter> registration = new FilterRegistrationBean<>(new RequestLogFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /** Fica dentro da cadeia do Spring Security, então já enxerga o usuário autenticado. */
    @Bean
    FilterRegistrationBean<RequestLogFilter.UserTag> requestLogUserTag() {
        FilterRegistrationBean<RequestLogFilter.UserTag> registration = new FilterRegistrationBean<>(new RequestLogFilter.UserTag());
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
