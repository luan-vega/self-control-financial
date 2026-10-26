package com.selfcontrol.app.shared.audit;

import java.time.Instant;
import java.util.UUID;

/** Uma linha da trilha de auditoria, para as telas (admin e atividade da conta). */
public record AuditEvent(long id, Instant occurredAt, UUID actorId, String actorEmail, UUID subjectId,
                         String subjectEmail, String action, String targetType, String targetId, Channel channel,
                         String ip, String details) {
}
