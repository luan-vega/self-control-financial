package com.selfcontrol.app.shared.security;

/** Papel da conta (coluna app_user.role). O admin também é usuário comum. */
public enum Role {

    USER, ADMIN;

    public String[] authorities() {
        return this == ADMIN ? new String[]{"ROLE_USER", "ROLE_ADMIN"} : new String[]{"ROLE_USER"};
    }
}
