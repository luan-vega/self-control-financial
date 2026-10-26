package com.selfcontrol.app.account;

/** Dados do cadastro aberto. */
public record Registration(String name, String email, String password, String currency, boolean acceptTerms) {
}
