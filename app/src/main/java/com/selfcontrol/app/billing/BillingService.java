package com.selfcontrol.app.billing;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.error.PlanLimitException;
import com.selfcontrol.app.shared.security.CurrentUser;
import com.selfcontrol.app.shared.security.UserContext;
import com.selfcontrol.app.shared.web.PublicUrl;
import com.selfcontrol.domain.billing.Plan;
import com.selfcontrol.domain.billing.Usage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Planos e cobrança. O plano de cada conta fica em subscription (sem linha = Free). Quem muda o
 * plano é o provedor de pagamento: o webhook do Stripe ou, na simulação, a tela de checkout falsa.
 * subscription não tem RLS (o webhook chega sem usuário); as consultas da conta logada filtram
 * por app_current_user_id().
 */
@Service
@Transactional
public class BillingService implements BillingClient {

    /** O que o webhook fez com um evento. */
    public enum Outcome { APPLIED, DUPLICATE, IGNORED, UNKNOWN_ACCOUNT }

    private record Row(UUID userId, Plan plan, SubscriptionStatus status, BillingProvider provider, String customerId,
                       Instant currentPeriodEnd, boolean cancelAtPeriodEnd) {
    }

    private static final String SELECT = """
            SELECT user_id, plan, status, provider, customer_id, current_period_end, cancel_at_period_end
              FROM subscription
            """;

    private final JdbcClient jdbc;
    private final UserContext users;
    private final PaymentGateway gateway;
    private final PublicUrl urls;
    private final AuditLog audit;

    BillingService(JdbcClient jdbc, UserContext users, PaymentGateway gateway, PublicUrl urls, AuditLog audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.gateway = gateway;
        this.urls = urls;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public Subscription subscription() {
        return row(users.current().id()).map(BillingService::effective).orElseGet(Subscription::free);
    }

    @Override
    @Transactional(readOnly = true)
    public Usage usage() {
        return subscription().plan().usage(createdThisMonth());
    }

    @Override
    @Transactional(readOnly = true)
    public void checkCanCreateTransactions(int count) {
        Usage usage = usage();
        if (!usage.allows(count)) {
            throw new PlanLimitException(usage.limit().orElseThrow());
        }
    }

    @Override
    public BillingProvider provider() {
        return gateway.provider();
    }

    @Override
    public URI startCheckout() {
        CurrentUser user = users.current();
        String email = jdbc.sql("SELECT email FROM app_user WHERE id = :id").param("id", user.id()).query(String.class).single();
        Optional<Row> row = row(user.id());
        URI checkout = gateway.checkout(new PaymentGateway.CheckoutRequest(user.id(), email,
                row.map(Row::customerId).orElse(null), urls.of("/billing?upgraded"), urls.of("/billing?canceled")));
        audit.record("billing.checkout_started", "subscription", user.id(), Map.of("provider", gateway.provider().name()));
        return checkout;
    }

    @Override
    @Transactional(readOnly = true)
    public URI manage() {
        String customer = row(users.current().id()).map(Row::customerId).orElse(null);
        if (customer == null) {
            return URI.create(urls.of("/billing"));
        }
        return gateway.portal(customer, urls.of("/billing"));
    }

    /** Simulação: o "pagamento" da tela de checkout falsa ativa o Pro por um mês. */
    @Override
    public void confirmMockCheckout() {
        requireMock();
        UUID userId = users.current().id();
        save(new Row(userId, Plan.PRO, SubscriptionStatus.ACTIVE, BillingProvider.MOCK, null,
                Instant.now().plusSeconds(30L * 24 * 3600), false), null);
        audit.record("billing.plan_changed", "subscription", userId, Map.of("plan", "PRO", "provider", "MOCK"));
    }

    /** Simulação: cancela na hora e volta para o Free. */
    @Override
    public void cancelMock() {
        requireMock();
        UUID userId = users.current().id();
        save(new Row(userId, Plan.FREE, SubscriptionStatus.CANCELED, BillingProvider.MOCK, null, null, false), null);
        audit.record("billing.plan_changed", "subscription", userId, Map.of("plan", "FREE", "provider", "MOCK"));
    }

    /**
     * Aplica um evento do Stripe (já com a assinatura conferida). Cada evento vale uma vez: o Stripe
     * reenvia em caso de erro ou demora, e o id fica guardado em billing_event.
     */
    public Outcome handle(JsonNode event) {
        String id = event.path("id").asString("");
        String type = event.path("type").asString("");
        if (id.isEmpty()) {
            return Outcome.IGNORED;
        }
        int inserted = jdbc.sql("INSERT INTO billing_event (id, type) VALUES (:id, :type) ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("type", type)
                .update();
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }
        JsonNode object = event.path("data").path("object");
        return switch (type) {
            case "checkout.session.completed" -> checkoutCompleted(object);
            case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted" ->
                    subscriptionChanged(object, type.endsWith("deleted"));
            case "invoice.payment_failed" -> invoice(object, SubscriptionStatus.PAST_DUE);
            case "invoice.paid" -> invoice(object, SubscriptionStatus.ACTIVE);
            default -> Outcome.IGNORED;
        };
    }

    private Outcome checkoutCompleted(JsonNode session) {
        UUID userId = uuid(text(session, "client_reference_id"))
                .or(() -> uuid(text(session.path("metadata"), "user_id")))
                .orElse(null);
        if (userId == null || !exists(userId)) {
            return Outcome.UNKNOWN_ACCOUNT;
        }
        Row before = row(userId).orElse(null);
        save(new Row(userId, Plan.PRO, SubscriptionStatus.ACTIVE, BillingProvider.STRIPE, text(session, "customer"),
                before == null ? null : before.currentPeriodEnd(), false), text(session, "subscription"));
        changed(userId, before, Plan.PRO, SubscriptionStatus.ACTIVE);
        return Outcome.APPLIED;
    }

    private Outcome subscriptionChanged(JsonNode sub, boolean deleted) {
        String externalId = text(sub, "id");
        String customer = text(sub, "customer");
        Optional<Row> existing = jdbc.sql(SELECT + " WHERE external_id = :ext OR (customer_id = :customer)")
                .param("ext", externalId)
                .param("customer", customer)
                .query(BillingService::map)
                .optional();
        UUID userId = existing.map(Row::userId)
                .or(() -> uuid(text(sub.path("metadata"), "user_id")).filter(this::exists))
                .orElse(null);
        if (userId == null) {
            return Outcome.UNKNOWN_ACCOUNT;
        }
        SubscriptionStatus status = deleted ? SubscriptionStatus.CANCELED : SubscriptionStatus.fromStripe(text(sub, "status"));
        Plan plan = status == SubscriptionStatus.CANCELED && deleted ? Plan.FREE : Plan.PRO;
        save(new Row(userId, plan, status, BillingProvider.STRIPE, customer, periodEnd(sub),
                sub.path("cancel_at_period_end").asBoolean(false)), externalId);
        changed(userId, existing.orElse(null), plan, status);
        return Outcome.APPLIED;
    }

    private Outcome invoice(JsonNode invoice, SubscriptionStatus status) {
        String customer = text(invoice, "customer");
        Optional<Row> existing = customer == null ? Optional.empty()
                : jdbc.sql(SELECT + " WHERE customer_id = :customer").param("customer", customer)
                .query(BillingService::map).optional();
        if (existing.isEmpty()) {
            return Outcome.UNKNOWN_ACCOUNT;
        }
        Row row = existing.get();
        if (row.plan() != Plan.PRO || row.status() == status) {
            return Outcome.IGNORED;
        }
        jdbc.sql("UPDATE subscription SET status = :status, updated_at = now() WHERE user_id = :id")
                .param("status", status.name())
                .param("id", row.userId())
                .update();
        changed(row.userId(), row, row.plan(), status);
        return Outcome.APPLIED;
    }

    private void changed(UUID userId, Row before, Plan plan, SubscriptionStatus status) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("plan", plan.name());
        details.put("status", status.name());
        details.put("previousPlan", before == null ? "FREE" : before.plan().name());
        audit.record(null, userId, "billing.plan_changed", "subscription", userId, details);
    }

    private void save(Row row, String externalId) {
        jdbc.sql("""
                        INSERT INTO subscription (user_id, plan, status, provider, customer_id, external_id,
                                                  current_period_end, cancel_at_period_end)
                        VALUES (:userId, :plan, :status, :provider, :customer, :external, :periodEnd, :cancelAtEnd)
                        ON CONFLICT (user_id) DO UPDATE
                           SET plan = excluded.plan, status = excluded.status, provider = excluded.provider,
                               customer_id = coalesce(excluded.customer_id, subscription.customer_id),
                               external_id = coalesce(excluded.external_id, subscription.external_id),
                               current_period_end = excluded.current_period_end,
                               cancel_at_period_end = excluded.cancel_at_period_end, updated_at = now()
                        """)
                .param("userId", row.userId())
                .param("plan", row.plan().name())
                .param("status", row.status().name())
                .param("provider", row.provider().name())
                .param("customer", row.customerId())
                .param("external", externalId)
                .param("periodEnd", row.currentPeriodEnd() == null ? null : Timestamp.from(row.currentPeriodEnd()))
                .param("cancelAtEnd", row.cancelAtPeriodEnd())
                .update();
    }

    private int createdThisMonth() {
        return jdbc.sql("SELECT count(*) FROM financial_transaction WHERE created_at >= date_trunc('month', now())")
                .query(Integer.class)
                .single();
    }

    private Optional<Row> row(UUID userId) {
        return jdbc.sql(SELECT + " WHERE user_id = :id").param("id", userId).query(BillingService::map).optional();
    }

    private boolean exists(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id").param("id", userId).query(Integer.class).single() > 0;
    }

    private void requireMock() {
        if (gateway.provider() != BillingProvider.MOCK) {
            throw new NotFoundException("Disponível só na cobrança simulada");
        }
    }

    /** Pro cancelado continua valendo até o fim do período pago. */
    private static Subscription effective(Row row) {
        boolean paidUntilLater = row.currentPeriodEnd() != null && row.currentPeriodEnd().isAfter(Instant.now());
        boolean pro = row.plan() == Plan.PRO && (row.status() != SubscriptionStatus.CANCELED || paidUntilLater);
        return new Subscription(pro ? Plan.PRO : Plan.FREE, row.status(), row.provider(), row.currentPeriodEnd(),
                row.cancelAtPeriodEnd(), row.customerId() != null);
    }

    /** Fim do período: na raiz da assinatura (APIs antigas) ou no primeiro item (APIs novas). */
    private static Instant periodEnd(JsonNode sub) {
        JsonNode end = sub.path("current_period_end");
        if (!end.isNumber()) {
            end = sub.path("items").path("data").path(0).path("current_period_end");
        }
        return end.isNumber() ? Instant.ofEpochSecond(end.asLong()) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static Optional<UUID> uuid(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        Timestamp end = rs.getTimestamp("current_period_end");
        return new Row(rs.getObject("user_id", UUID.class), Plan.valueOf(rs.getString("plan")),
                SubscriptionStatus.valueOf(rs.getString("status")), BillingProvider.valueOf(rs.getString("provider")),
                rs.getString("customer_id"), end == null ? null : end.toInstant(), rs.getBoolean("cancel_at_period_end"));
    }
}
