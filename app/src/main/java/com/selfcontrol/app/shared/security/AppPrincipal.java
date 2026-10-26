package com.selfcontrol.app.shared.security;

import java.util.UUID;

/** Principal autenticado que já conhece o id do usuário em app_user (login por senha ou Google). */
public interface AppPrincipal {

    UUID userId();
}
