#!/bin/sh
set -eu
umask 077

# Render the ACL only into tmpfs. A SHA-256 password hash avoids interpreting
# whitespace or Redis ACL syntax from a raw password.
test -s /run/secrets/redis-password
password="$(cat /run/secrets/redis-password)"
test -n "$password"
password_hash="$(printf '%s' "$password" | sha256sum)"
unset password
password_hash="${password_hash%% *}"
printf 'user default off\nuser notification on #%s ~cookie:notification:* +ping +hello +client|setname +client|setinfo +select +zadd +zrem +zrange +zrangebyscore +zcard +zscore +del +exists +expire\n' \
  "$password_hash" >/tmp/users.acl
exec redis-server /etc/redis/redis.conf
