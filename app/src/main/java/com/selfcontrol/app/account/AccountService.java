package com.selfcontrol.app.account;

import com.selfcontrol.app.shared.audit.AuditLog;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.security.Sessions;
import com.selfcontrol.app.shared.web.PublicUrl;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cadastro aberto, confirmação de e-mail, troca e recuperação de senha e aceite dos termos.
 * app_user não tem RLS (é lida antes do login), então as operações da conta logada filtram por
 * app_current_user_id().
 */
@Service
@Transactional
public class AccountService {

    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 128;
    static final int MAX_NAME = 80;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    private record Account(UUID id, String email, String name, boolean active) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final UserTokens tokens;
    private final Mailer mailer;
    private final PublicUrl urls;
    private final AdminEmails admins;
    private final Sessions sessions;
    private final AuditLog audit;

    AccountService(JdbcClient jdbc, PasswordEncoder passwords, UserTokens tokens, Mailer mailer, PublicUrl urls,
                   AdminEmails admins, Sessions sessions, AuditLog audit) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.mailer = mailer;
        this.urls = urls;
        this.admins = admins;
        this.sessions = sessions;
        this.audit = audit;
    }

    /** Cria a conta (ainda com e-mail não confirmado) e envia o link de confirmação. */
    public UUID register(Registration r) {
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new InvalidInputException("name", "Informe seu nome (até " + MAX_NAME + " caracteres)");
        }
        String email = normalizeEmail(r.email());
        validatePassword("password", r.password());
        if (!SupportedCurrencies.isSupported(r.currency())) {
            throw new InvalidInputException("currency", "Escolha uma moeda da lista");
        }
        if (!r.acceptTerms()) {
            throw new InvalidInputException("acceptTerms", "É preciso aceitar os Termos de Uso e a Política de Privacidade");
        }
        if (find(email).isPresent()) {
            throw new InvalidInputException("email", "Já existe uma conta com este e-mail");
        }

        UUID id = jdbc.sql("""
                        INSERT INTO app_user (email, display_name, password_hash, default_currency, role,
                                              terms_accepted_at, terms_version)
                        VALUES (:email, :name, :hash, :currency, :role, now(), :terms)
                        RETURNING id
                        """)
                .param("email", email)
                .param("name", name)
                .param("hash", passwords.encode(r.password()))
                .param("currency", r.currency())
                .param("role", admins.isAdmin(email) ? "ADMIN" : "USER")
                .param("terms", Legal.VERSION)
                .query(UUID.class)
                .single();
        audit.record(id, id, "account.registered", "user", id, Map.of("terms", Legal.VERSION));
        sendVerification(new Account(id, email, name, true));
        return id;
    }

    /** Confirma o e-mail pelo link. Falso se o link venceu, já foi usado ou não existe. */
    public boolean verifyEmail(String token) {
        Optional<UUID> owner = tokens.consume(token, TokenPurpose.VERIFY_EMAIL);
        owner.ifPresent(id -> {
            jdbc.sql("UPDATE app_user SET email_verified_at = now(), updated_at = now() WHERE id = :id")
                    .param("id", id)
                    .update();
            audit.record(id, id, "account.email_verified", "user", id, Map.of());
        });
        return owner.isPresent();
    }

    /** Reenvia o link para a conta logada (se o e-mail ainda não foi confirmado). */
    public void resendVerification() {
        Account account = current();
        boolean verified = jdbc.sql("SELECT email_verified_at IS NOT NULL FROM app_user WHERE id = :id")
                .param("id", account.id())
                .query(Boolean.class)
                .single();
        if (!verified) {
            sendVerification(account);
        }
    }

    /**
     * Envia o link de troca de senha, se a conta existe e está ativa. Não conta para quem pediu se
     * o e-mail existe: a tela mostra sempre a mesma mensagem.
     */
    public void requestPasswordReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        find(email.strip()).filter(Account::active).ifPresent(account -> {
            String token = tokens.issue(account.id(), TokenPurpose.RESET_PASSWORD);
            String link = link("/reset-password", token);
            mailer.send(new Mailer.Message(account.email(), "Troque sua senha do Self Control", """
                    Olá, %s.

                    Recebemos um pedido para trocar a senha da sua conta. Para escolher uma nova senha, abra:
                    %s

                    O link vale por 1 hora. Se não foi você, ignore este e-mail: sua senha continua a mesma.
                    """.formatted(account.name(), link)));
            audit.record(null, account.id(), "account.password_reset_requested", "user", account.id(), Map.of());
        });
    }

    @Transactional(readOnly = true)
    public boolean resetTokenValid(String token) {
        return tokens.isValid(token, TokenPurpose.RESET_PASSWORD);
    }

    /** Troca a senha pelo link do e-mail e encerra as sessões abertas. Falso se o link não vale mais. */
    public boolean resetPassword(String token, String newPassword) {
        validatePassword("password", newPassword);
        Optional<UUID> owner = tokens.consume(token, TokenPurpose.RESET_PASSWORD);
        owner.ifPresent(id -> {
            // Quem recebeu o link no e-mail provou que é dono do endereço.
            String email = jdbc.sql("""
                            UPDATE app_user SET password_hash = :hash, updated_at = now(),
                                   email_verified_at = coalesce(email_verified_at, now())
                             WHERE id = :id RETURNING email
                            """)
                    .param("hash", passwords.encode(newPassword))
                    .param("id", id)
                    .query(String.class)
                    .single();
            sessions.endAll(email);
            audit.record(null, id, "account.password_reset", "user", id, Map.of());
        });
        return owner.isPresent();
    }

    /** Troca a senha da conta logada. Contas só com Google podem criar uma senha sem informar a atual. */
    public void changePassword(String currentPassword, String newPassword) {
        UUID id = current().id();
        String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
        if (hash != null && (currentPassword == null || !passwords.matches(currentPassword, hash))) {
            throw new InvalidInputException("currentPassword", "Senha atual incorreta");
        }
        validatePassword("newPassword", newPassword);
        jdbc.sql("UPDATE app_user SET password_hash = :hash, updated_at = now() WHERE id = :id")
                .param("hash", passwords.encode(newPassword))
                .param("id", id)
                .update();
        audit.record("account.password_changed", "user", id, Map.of());
    }

    @Transactional(readOnly = true)
    public boolean termsAccepted() {
        return jdbc.sql("SELECT terms_version = :version FROM app_user WHERE id = app_current_user_id()")
                .param("version", Legal.VERSION)
                .query(Boolean.class)
                .optional()
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }

    public void acceptTerms() {
        UUID id = current().id();
        jdbc.sql("UPDATE app_user SET terms_accepted_at = now(), terms_version = :version, updated_at = now() WHERE id = :id")
                .param("version", Legal.VERSION)
                .param("id", id)
                .update();
        audit.record("account.terms_accepted", "user", id, Map.of("terms", Legal.VERSION));
    }

    private void sendVerification(Account account) {
        String token = tokens.issue(account.id(), TokenPurpose.VERIFY_EMAIL);
        mailer.send(new Mailer.Message(account.email(), "Confirme seu e-mail no Self Control", """
                Olá, %s!

                Para confirmar seu e-mail, abra:
                %s

                O link vale por 3 dias. Se você não criou uma conta, ignore este e-mail.
                """.formatted(account.name(), link("/verify-email", token))));
    }

    private String link(String path, String token) {
        return UriComponentsBuilder.fromUriString(urls.of(path)).queryParam("token", token).toUriString();
    }

    private Optional<Account> find(String email) {
        return jdbc.sql("SELECT id, email, display_name, active FROM app_user WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getBoolean("active")))
                .optional();
    }

    private Account current() {
        return jdbc.sql("SELECT id, email, display_name, active FROM app_user WHERE id = app_current_user_id()")
                .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getBoolean("active")))
                .optional()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Nenhum usuário conhecido na sessão"));
    }

    static String normalizeEmail(String email) {
        String value = email == null ? "" : email.strip();
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) {
            throw new InvalidInputException("email", "E-mail inválido");
        }
        // A parte local fica como veio; o domínio não diferencia maiúsculas.
        int at = value.lastIndexOf('@');
        return value.substring(0, at) + value.substring(at).toLowerCase(Locale.ROOT);
    }

    static void validatePassword(String field, String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new InvalidInputException(field, "A senha precisa ter pelo menos " + MIN_PASSWORD + " caracteres");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new InvalidInputException(field, "A senha pode ter até " + MAX_PASSWORD + " caracteres");
        }
    }
}
