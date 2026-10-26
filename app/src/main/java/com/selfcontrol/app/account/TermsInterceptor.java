package com.selfcontrol.app.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Quem ainda não aceitou a versão vigente dos termos (entrou pelo Google ou tem conta antiga) é
 * levado à tela de aceite antes de usar as telas. A API não passa por aqui: quem cria chave de API
 * já passou pelas telas. O aceite fica marcado na sessão para não consultar o banco a cada página.
 */
@Component
class TermsInterceptor implements HandlerInterceptor {

    static final String ACCEPTED = TermsInterceptor.class.getName() + ".accepted";

    private final AccountService accounts;

    TermsInterceptor(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session != null && Legal.VERSION.equals(session.getAttribute(ACCEPTED))) {
            return true;
        }
        if (accounts.termsAccepted()) {
            if (session != null) {
                session.setAttribute(ACCEPTED, Legal.VERSION);
            }
            return true;
        }
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", request.getContextPath() + "/legal/accept");
            return false;
        }
        response.sendRedirect(request.getContextPath() + "/legal/accept");
        return false;
    }
}
