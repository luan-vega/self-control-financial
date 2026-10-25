// Envia o token CSRF do Spring Security em toda requisição HTMX.
document.addEventListener("htmx:configRequest", (event) => {
    const token = document.querySelector("meta[name='_csrf']");
    const header = document.querySelector("meta[name='_csrf_header']");
    if (token && header) {
        event.detail.headers[header.content] = token.content;
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
