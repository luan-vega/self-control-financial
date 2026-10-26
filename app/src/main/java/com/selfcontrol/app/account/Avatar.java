package com.selfcontrol.app.account;

/** Foto enviada pelo usuário. */
public record Avatar(String contentType, byte[] bytes) {
}
