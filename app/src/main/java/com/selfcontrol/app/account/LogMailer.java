package com.selfcontrol.app.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Mailer simulado: escreve o e-mail no log em vez de enviar (o link aparece no console).
 * Guarda as últimas mensagens em memória para os testes. Para enviar de verdade, troque por uma
 * implementação SMTP/API de e-mail (veja docs/deployment.md).
 */
@Component
class LogMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);
    private static final int KEEP = 50;

    private final Deque<Message> recent = new ArrayDeque<>();

    @Override
    public void send(Message message) {
        log.atInfo()
                .addKeyValue("mail.to", message.to())
                .addKeyValue("mail.subject", message.subject())
                .log("E-mail (simulado) para {}: {}\n{}", message.to(), message.subject(), message.body());
        synchronized (recent) {
            recent.addFirst(message);
            while (recent.size() > KEEP) {
                recent.removeLast();
            }
        }
    }

    /** Mensagens mais recentes primeiro. */
    List<Message> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }
}
