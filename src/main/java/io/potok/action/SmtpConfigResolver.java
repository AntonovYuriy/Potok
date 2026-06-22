package io.potok.action;

/** Supplies the effective SMTP config (DB → env → none) at send time. */
@FunctionalInterface
public interface SmtpConfigResolver {

    SmtpConfig resolve();
}
