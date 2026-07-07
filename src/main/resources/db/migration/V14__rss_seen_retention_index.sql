-- M12: rss_seen is now purged by retention (RetentionPurger). Index seen_at so the
-- nightly `delete from rss_seen where seen_at < cutoff` doesn't scan the whole table.
create index rss_seen_seen_at_idx on rss_seen (seen_at);
