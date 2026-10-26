package com.selfcontrol.app.importing;

import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.InvalidInputException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Telas de importação: upload (arrastar e soltar), preview com as linhas escolhidas, confirmação
 * e histórico. O upload e a confirmação são HTMX e trocam só o bloco #import-result.
 */
@Controller
@RequestMapping("/imports")
class ImportPageController {

    static final String RESULT = "imports/preview :: result";

    private final ImportClient imports;

    ImportPageController(ImportClient imports) {
        this.imports = imports;
    }

    @GetMapping
    String history(Model model) {
        model.addAttribute("batches", imports.history());
        return "imports/history";
    }

    @GetMapping("/new")
    String upload() {
        return "imports/new";
    }

    @PostMapping
    String preview(@RequestParam(name = "file", required = false) MultipartFile file,
                   @RequestHeader(name = "HX-Request", required = false) String htmx,
                   Model model) throws IOException {
        try {
            ImportBatch batch = imports.preview(file == null ? null : file.getOriginalFilename(),
                    file == null ? null : file.getBytes());
            if (htmx == null) {
                return "redirect:/imports/" + batch.id();
            }
            return result(model, batch, null);
        } catch (InvalidInputException e) {
            model.addAttribute("error", e.getMessage());
            return htmx != null ? RESULT : "imports/new";
        }
    }

    @GetMapping("/{id}")
    String detail(@PathVariable UUID id, Model model) {
        result(model, imports.get(id), null);
        return "imports/detail";
    }

    @PostMapping("/{id}/confirm")
    String confirm(@PathVariable UUID id, @RequestParam(name = "rowIds", required = false) List<UUID> rowIds,
                   @RequestHeader(name = "HX-Request", required = false) String htmx,
                   Model model, HttpServletResponse response) {
        try {
            ImportBatch batch = imports.confirm(id, rowIds);
            return redirect("/inbox?imported=" + batch.importedRows(), htmx, response);
        } catch (InvalidInputException | ConflictException e) {
            result(model, imports.get(id), e.getMessage());
            return htmx != null ? RESULT : "imports/detail";
        }
    }

    @PostMapping("/{id}/cancel")
    String cancel(@PathVariable UUID id, @RequestHeader(name = "HX-Request", required = false) String htmx,
                  Model model, HttpServletResponse response) {
        try {
            imports.cancel(id);
            return redirect("/imports/new", htmx, response);
        } catch (ConflictException e) {
            result(model, imports.get(id), e.getMessage());
            return htmx != null ? RESULT : "imports/detail";
        }
    }

    private String result(Model model, ImportBatch batch, String error) {
        model.addAttribute("batch", batch);
        model.addAttribute("rows", imports.rows(batch.id()));
        model.addAttribute("error", error);
        return RESULT;
    }

    /** Com HTMX a navegação é feita pelo header HX-Redirect; sem HTMX, redirect comum. */
    private static String redirect(String url, String htmx, HttpServletResponse response) {
        if (htmx == null) {
            return "redirect:" + url;
        }
        response.setHeader("HX-Redirect", url);
        return "imports/preview :: empty";
    }
}
