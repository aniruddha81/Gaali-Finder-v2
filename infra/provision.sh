#!/usr/bin/env bash
# Creates the Gaali Finder stack on Oracle Cloud, inside Always Free limits:
#   $1 budget alert -> compartment -> VCN/subnet/firewall -> A1 VM (cloud-init) -> backup bucket
#   -> instance-principal access for the nightly backup.
#
# Needs: the `oci` CLI logged in, and
#   DOMAIN       API hostname that will point at the VM, e.g. api.example.com
#   ALERT_EMAIL  where the budget alert goes
# Optional: ADMIN_CIDR restricts SSH to one address, e.g. 203.0.113.7/32 (default: anywhere, so
# GitHub Actions and a changing home IP can reach it; sshd is key-only and fail2ban is on), SSH_PUBKEY (default ~/.ssh/gaali_vm.pub; generated if missing), SHAPE=micro to use
# the AMD micro fallback when Mumbai has no A1 capacity, RETRIES (default 60, one per minute).
#
# Safe to re-run: every step looks the resource up by name first.
set -euo pipefail

: "${DOMAIN:?set DOMAIN}" "${ALERT_EMAIL:?set ALERT_EMAIL}"
ADMIN_CIDR=${ADMIN_CIDR:-0.0.0.0/0}
SSH_PUBKEY=${SSH_PUBKEY:-$HOME/.ssh/gaali_vm.pub}
RETRIES=${RETRIES:-60}
here=$(cd "$(dirname "$0")" && pwd)

tenancy=$(awk -F= '/^tenancy=/{print $2}' ~/.oci/config)
region=$(awk -F= '/^region=/{print $2}' ~/.oci/config)

if [ ! -f "$SSH_PUBKEY" ]; then
  ssh-keygen -t ed25519 -N '' -C gaali-vm -f "${SSH_PUBKEY%.pub}"
  echo "generated SSH key ${SSH_PUBKEY%.pub}"
fi

echo "== budget: any real charge emails $ALERT_EMAIL"
if ! oci budgets budget budget list --compartment-id "$tenancy" --all     --query 'data[?"display-name"==`gaali-1usd`]|[0].id' --raw-output 2>/dev/null | grep -qv '^null$'; then
  budget=$(oci budgets budget budget create --compartment-id "$tenancy" --amount 1 \
    --reset-period MONTHLY --display-name gaali-1usd --target-type COMPARTMENT \
    --targets "[\"$tenancy\"]" --query data.id --raw-output)
  oci budgets budget alert-rule create --budget-id "$budget" --type ACTUAL \
    --threshold 1 --threshold-type PERCENTAGE --recipients "$ALERT_EMAIL" \
    --display-name any-charge --message "Gaali Finder spent money - something left Always Free." >/dev/null
fi

echo "== compartment"
comp=$(oci iam compartment list --compartment-id "$tenancy" --name gaali --lifecycle-state ACTIVE \
  --query 'data[0].id' --raw-output 2>/dev/null | grep -v '^null$' || true)
[ -n "$comp" ] || comp=$(oci iam compartment create --compartment-id "$tenancy" --name gaali \
  --description "Gaali Finder" --wait-for-state ACTIVE --query data.id --raw-output)

echo "== network"
vcn=$(oci network vcn list --compartment-id "$comp" --display-name gaali --query 'data[0].id' --raw-output 2>/dev/null | grep -v '^null$' || true)
if [ -z "$vcn" ]; then
  vcn=$(oci network vcn create --compartment-id "$comp" --cidr-block 10.0.0.0/16 \
    --display-name gaali --dns-label gaali --wait-for-state AVAILABLE --query data.id --raw-output)
  igw=$(oci network internet-gateway create --compartment-id "$comp" --vcn-id "$vcn" \
    --is-enabled true --display-name gaali-igw --wait-for-state AVAILABLE --query data.id --raw-output)
  rt=$(oci network vcn get --vcn-id "$vcn" --query 'data."default-route-table-id"' --raw-output)
  oci network route-table update --rt-id "$rt" --force \
    --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$igw\"}]" >/dev/null
  sl=$(oci network vcn get --vcn-id "$vcn" --query 'data."default-security-list-id"' --raw-output)
  tcp() { printf '{"protocol":"6","source":"%s","tcpOptions":{"destinationPortRange":{"min":%s,"max":%s}}}' "$1" "$2" "$2"; }
  oci network security-list update --security-list-id "$sl" --force \
    --ingress-security-rules "[$(tcp 0.0.0.0/0 80),$(tcp 0.0.0.0/0 443),$(tcp "$ADMIN_CIDR" 22),{\"protocol\":\"1\",\"source\":\"0.0.0.0/0\",\"icmpOptions\":{\"type\":3,\"code\":4}}]" \
    --egress-security-rules '[{"protocol":"all","destination":"0.0.0.0/0"}]' >/dev/null
  oci network subnet create --compartment-id "$comp" --vcn-id "$vcn" --cidr-block 10.0.0.0/24 \
    --display-name gaali-public --dns-label pub --wait-for-state AVAILABLE >/dev/null
fi
subnet=$(oci network subnet list --compartment-id "$comp" --vcn-id "$vcn" --query 'data[0].id' --raw-output)

echo "== backup bucket"
ns=$(oci os ns get --query data --raw-output)
oci os bucket get --namespace-name "$ns" --bucket-name gaali-backups >/dev/null 2>&1 || \
  oci os bucket create --namespace-name "$ns" --compartment-id "$comp" --name gaali-backups >/dev/null

echo "== VM"
inst=$(oci compute instance list --compartment-id "$comp" --display-name gaali-api \
  --query 'data[?"lifecycle-state"!=`TERMINATED` && "lifecycle-state"!=`TERMINATING`]|[0].id' --raw-output 2>/dev/null | grep -v '^null$' || true)
if [ -z "$inst" ]; then
  userdata=$(mktemp)
  sed -e "s|__DOMAIN__|$DOMAIN|g" -e "s|__NAMESPACE__|$ns|g" \
      -e "s|__COMPARTMENT_OCID__|$comp|g" -e "s|__REGION__|$region|g" "$here/cloud-init.yaml" > "$userdata"
  ad=$(oci iam availability-domain list --query 'data[0].name' --raw-output)
  if [ "${SHAPE:-a1}" = micro ]; then
    shape=VM.Standard.E2.1.Micro; shape_cfg=(); arch=x86_64
  else
    shape=VM.Standard.A1.Flex; shape_cfg=(--shape-config '{"ocpus":1,"memoryInGBs":6}'); arch=aarch64
  fi
  image=$(oci compute image list --compartment-id "$tenancy" --operating-system "Canonical Ubuntu" \
    --operating-system-version 24.04 --shape "$shape" --sort-by TIMECREATED --sort-order DESC \
    --query 'data[0].id' --raw-output)
  for ((i = 1; i <= RETRIES; i++)); do
    if inst=$(oci compute instance launch --compartment-id "$comp" --availability-domain "$ad" \
        --shape "$shape" "${shape_cfg[@]}" --image-id "$image" --subnet-id "$subnet" \
        --assign-public-ip true --display-name gaali-api --boot-volume-size-in-gbs 50 \
        --ssh-authorized-keys-file "$SSH_PUBKEY" --user-data-file "$userdata" \
        --wait-for-state RUNNING --query data.id --raw-output 2>/tmp/launch.err); then
      break
    fi
    if grep -qi "out of host capacity\|OutOfHostCapacity\|TooManyRequests" /tmp/launch.err; then
      echo "no $shape capacity yet, or throttled (try $i/$RETRIES), waiting a minute"; sleep 60
    else
      cat /tmp/launch.err >&2; exit 1
    fi
  done
  rm -f "$userdata"
  [ -n "$inst" ] || { echo "gave up waiting for capacity; re-run with SHAPE=micro" >&2; exit 1; }
fi

echo "== backup access for the VM (instance principal)"
dg=$(oci iam dynamic-group list --name gaali-vm --query 'data[0].id' --raw-output 2>/dev/null | grep -v '^null$' || true)
if [ -z "$dg" ]; then
  oci iam dynamic-group create --name gaali-vm --description "Gaali Finder VM"     --matching-rule "ALL {instance.id = '$inst'}" >/dev/null
else  # a rebuilt VM has a new id, so the rule must follow it
  oci iam dynamic-group update --dynamic-group-id "$dg" --matching-rule "ALL {instance.id = '$inst'}" --force >/dev/null
fi
oci iam policy list --compartment-id "$tenancy" --name gaali-vm-backup --query 'data[0].id' --raw-output 2>/dev/null | grep -qv '^null$' || \
  oci iam policy create --compartment-id "$tenancy" --name gaali-vm-backup --description "Gaali VM writes its backups" \
    --statements "[\"Allow dynamic-group gaali-vm to manage objects in compartment gaali where target.bucket.name='gaali-backups'\",\"Allow dynamic-group gaali-vm to read buckets in compartment gaali\"]" >/dev/null

ip=$(oci compute instance list-vnics --instance-id "$inst" --query 'data[0]."public-ip"' --raw-output)
cat <<EOF

Done. VM public IP: $ip

Next:
  1. DNS: create an A record  $DOMAIN -> $ip   (Caddy fetches the certificate once it resolves)
  2. First boot takes a few minutes. Then edit the secrets:
       ssh -i ${SSH_PUBKEY%.pub} ubuntu@$ip
       sudoedit /etc/gaali/api.env      # GOOGLE_WEB_CLIENT_ID, BREVO_API_KEY, MAIL_FROM
  3. infra/deploy.sh ubuntu@$ip
  4. curl https://$DOMAIN/healthz
EOF
