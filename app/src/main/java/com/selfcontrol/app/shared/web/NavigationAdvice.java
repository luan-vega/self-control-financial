package com.selfcontrol.app.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Expõe a seção atual para a barra de navegação destacar o item certo. */
@ControllerAdvice(annotations = Controller.class)
class NavigationAdvice {

    @ModelAttribute("section")
    String section(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/transactions")) {
            return "transactions";
        }
        if (path.startsWith("/reports")) {
            return "reports";
        }
        if (path.startsWith("/categories")) {
            return "categories";
        }
        return "home";
    }
}
