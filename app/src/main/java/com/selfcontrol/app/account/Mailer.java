package com.selfcontrol.app.account;

/** Envio de e-mails transacionais (confirmação de e-mail, troca de senha). */
public interface Mailer {

    record Message(String to, String subject, String body) {
    }

    void send(Message message);
}
