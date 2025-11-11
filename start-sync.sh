#!/bin/bash
set -euEo pipefail

on_exit() {
    terminal-notifier -group "rsync net-perspective" -message "rsync stopped"
}
trap on_exit EXIT

while true; do
    rsync \
        `# Let the command fail:` \
        -e 'ssh -o PasswordAuthentication=no' \
        -az \
        --chown=net-perspective:net-perspective \
        --chmod=D775,F664 \
        --delete \
        --exclude=/.git/ \
        --exclude=.tmp \
        --exclude=.log \
        --exclude=tmp \
        --exclude=logs \
        --exclude=root \
         . \
        server-alpha:/opt/net-perspective/
    sleep 1
done &> tmp/sync.log
