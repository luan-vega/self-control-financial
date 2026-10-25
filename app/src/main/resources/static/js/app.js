// Envia o token CSRF do Spring Security em toda requisição HTMX.
document.addEventListener("htmx:configRequest", (event) => {
    const token = document.querySelector("meta[name='_csrf']");
    const header = document.querySelector("meta[name='_csrf_header']");
    if (token && header) {
        event.detail.headers[header.content] = token.content;
    }
});

// Service worker mínimo: permite instalar como app no iPhone (Adicionar à Tela de Início).
if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
}
