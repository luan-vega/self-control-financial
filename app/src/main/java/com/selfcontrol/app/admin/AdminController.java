package com.selfcontrol.app.admin;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.security.AppPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/** Painel de admin (só ROLE_ADMIN, ver SecurityConfig): usuários e trilha de auditoria. */
@Controller
@RequestMapping("/admin")
class AdminController {

    private static final String ROW = "admin/user-row :: row";

    private final AdminService admin;
    private final AuditLog audit;

    AdminController(AdminService admin, AuditLog audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping
    String index() {
        return "redirect:/admin/users";
    }

    @GetMapping("/users")
    String users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, Model model) {
        List<AdminUser> found = admin.search(q, page);
        model.addAttribute("stats", admin.stats());
        model.addAttribute("users", found.stream().limit(AdminService.PAGE).toList());
        model.addAttribute("hasNext", found.size() > AdminService.PAGE);
        model.addAttribute("page", Math.max(0, page));
        model.addAttribute("q", q);
        return "admin/users";
    }

    @PostMapping("/users/{id}/active")
    String setActive(@PathVariable UUID id, @RequestParam boolean active,
                     @AuthenticationPrincipal AppPrincipal me, Model model) {
        try {
            model.addAttribute("u", admin.setActive(me.userId(), id, active));
        } catch (ConflictException e) {
            model.addAttribute("u", admin.get(id));
            model.addAttribute("rowError", e.getMessage());
        }
        return ROW;
    }

    @GetMapping("/audit")
    String audit(@RequestParam(required = false) UUID user, Model model) {
        model.addAttribute("events", audit.recent(user, 200));
        model.addAttribute("filtered", user == null ? null : admin.get(user));
        return "admin/audit";
    }
}
