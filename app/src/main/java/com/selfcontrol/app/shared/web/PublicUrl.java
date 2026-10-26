package com.selfcontrol.app.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Monta links absolutos para e-mails e para o Stripe. Em produção use app.public-url
 * (ex.: https://app.exemplo.com): confiar no Host da requisição permitiria a alguém forjar o
 * domínio do link de troca de senha.
 */
@Component
public class PublicUrl {

    private final String base;

    PublicUrl(@Value("${app.public-url:}") String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String of(String path) {
        if (!base.isBlank()) {
            return base + path;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
    }
}
