package com.selfcontrol.app.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class LoginController {

    private final boolean googleEnabled;

    LoginController(ObjectProvider<ClientRegistrationRepository> oauthClients) {
        this.googleEnabled = oauthClients.getIfAvailable() != null;
    }

    @GetMapping("/login")
    String login(Model model) {
        model.addAttribute("googleEnabled", googleEnabled);
        return "auth/login";
    }
}
