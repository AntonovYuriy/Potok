-- M14 resource diet: remember HTTP validators per poll/rss workflow so ticks
-- can send If-None-Match / If-Modified-Since and skip the body on 304.
alter table poll_state
    add column etag          text,
    add column last_modified text;
