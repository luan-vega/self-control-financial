package com.selfcontrol.app.account;

/** Campos da tela de cadastro. As regras ficam no {@link AccountService}. */
public class RegistrationForm {

    private String name;
    private String email;
    private String password;
    private String currency = "BRL";
    private boolean acceptTerms;

    Registration toRegistration() {
        return new Registration(name, email, password, currency, acceptTerms);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isAcceptTerms() {
        return acceptTerms;
    }

    public void setAcceptTerms(boolean acceptTerms) {
        this.acceptTerms = acceptTerms;
    }
}
