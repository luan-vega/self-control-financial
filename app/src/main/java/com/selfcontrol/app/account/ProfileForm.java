package com.selfcontrol.app.account;

/** Campos editáveis do perfil. */
public class ProfileForm {

    private String name;
    private String currency;

    static ProfileForm of(Profile p) {
        ProfileForm form = new ProfileForm();
        form.setName(p.name());
        form.setCurrency(p.currency().getCurrencyCode());
        return form;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
