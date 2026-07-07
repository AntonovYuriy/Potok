package io.potok.common;

/**
 * Masking for identifiers that must not appear verbatim outside the sending
 * path itself. Telegram chat ids are personal identifiers: the recipients API
 * masks them, and logs and step outputs must match — a screenshot, a log
 * aggregator, or the executions view should never leak a full chat id.
 */
public final class Mask {

    private Mask() {
    }

    /** Same shape the recipients endpoint uses: {@code •••} + last 4 digits. */
    public static String chatId(String chatId) {
        if (chatId == null || chatId.length() <= 4) {
            return "•••";
        }
        return "•••" + chatId.substring(chatId.length() - 4);
    }
}
