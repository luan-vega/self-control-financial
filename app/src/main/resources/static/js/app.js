// Envia o token CSRF do Spring Security em toda requisição HTMX.
document.addEventListener("htmx:configRequest", (event) => {
    const token = document.querySelector("meta[name='_csrf']");
    const header = document.querySelector("meta[name='_csrf_header']");
    if (token && header) {
        event.detail.headers[header.content] = token.content;
    }
});

// Botões com data-reload-on-success recarregam a página quando a requisição HTMX dá certo
// (no lugar de hx-on, que exigiria 'unsafe-eval' na Content-Security-Policy).
document.addEventListener("htmx:afterRequest", (event) => {
    if (event.detail.successful && event.detail.elt.hasAttribute("data-reload-on-success")) {
        window.location.reload();
    }
});

// Modal: fecha no X, no fundo escuro ou com Esc. O conteúdo vem do servidor via HTMX.
function closeModal() {
    const modal = document.getElementById("modal");
    if (modal) {
        modal.innerHTML = "";
    }
}

document.addEventListener("click", (event) => {
    if (event.target.closest("[data-close-modal]")) {
        closeModal();
    }
});

document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        closeModal();
    }
});

// Service worker mínimo: permite instalar como app no iPhone (Adicionar à Tela de Início).
if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
}

// Upload por arrastar e soltar: o arquivo solto vai para o input, e o change envia o formulário (HTMX).
document.addEventListener("dragover", (event) => {
    const zone = event.target.closest("[data-dropzone]");
    if (zone) {
        event.preventDefault();
        zone.dataset.dragging = "";
    }
});

document.addEventListener("dragleave", (event) => {
    const zone = event.target.closest("[data-dropzone]");
    if (zone && !zone.contains(event.relatedTarget)) {
        delete zone.dataset.dragging;
    }
});

document.addEventListener("drop", (event) => {
    const zone = event.target.closest("[data-dropzone]");
    if (!zone) {
        return;
    }
    event.preventDefault();
    delete zone.dataset.dragging;
    const input = zone.querySelector("input[type=file]");
    if (input && event.dataTransfer.files.length > 0) {
        input.files = event.dataTransfer.files;
        input.dispatchEvent(new Event("change", { bubbles: true }));
    }
});

// Mostra o nome do arquivo escolhido na área de upload.
document.addEventListener("change", (event) => {
    const input = event.target;
    const zone = input.closest && input.closest("[data-dropzone]");
    if (zone && input.files && input.files.length > 0) {
        const label = zone.querySelector("[data-file-name]");
        if (label) {
            label.textContent = input.files[0].name;
        }
    }
});

// "Marcar todas": data-check-all=".seletor" marca/desmarca as caixas habilitadas.
document.addEventListener("change", (event) => {
    const selector = event.target.dataset && event.target.dataset.checkAll;
    if (selector) {
        document.querySelectorAll(selector).forEach((box) => {
            if (!box.disabled) {
                box.checked = event.target.checked;
            }
        });
    }
});

// Botão copiar: data-copy="#id-do-input".
document.addEventListener("click", (event) => {
    const button = event.target.closest("[data-copy]");
    if (!button) {
        return;
    }
    const field = document.querySelector(button.dataset.copy);
    if (field && navigator.clipboard) {
        navigator.clipboard.writeText(field.value).then(() => {
            const original = button.textContent;
            button.textContent = "Copiado";
            setTimeout(() => { button.textContent = original; }, 1500);
        });
    } else if (field) {
        field.select();
    }
});

// Escolher o arquivo já envia o formulário (foto do perfil).
document.addEventListener("change", (event) => {
    const input = event.target.closest("input[data-auto-submit]");
    if (input && input.files && input.files.length > 0) {
        input.form.requestSubmit();
    }
});
