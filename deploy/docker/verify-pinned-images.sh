#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -eq 0 ]]; then
  printf 'usage: %s <compose-or-dockerfile> [...]\n' "$0" >&2
  exit 2
fi

for file in "$@"; do
  [[ -f "${file}" ]] || {
    printf 'image-pin verification: file not found: %s\n' "${file}" >&2
    exit 1
  }
done

references=()
while IFS= read -r reference; do
  references+=("${reference}")
done < <(
  awk '
    /^#[[:space:]]*syntax=/ {
      reference = $0
      sub(/^#[[:space:]]*syntax=/, "", reference)
      print reference
    }
    /^[[:space:]]*image:[[:space:]]*/ {
      reference = $0
      sub(/^[[:space:]]*image:[[:space:]]*/, "", reference)
      if (reference !~ /^\$\{/) print reference
    }
    /^FROM[[:space:]]+/ {
      reference = $2
      print reference
    }
  ' "$@" | sort -u
)

[[ ${#references[@]} -gt 0 ]] || {
  printf 'image-pin verification: no external image references found\n' >&2
  exit 1
}

for reference in "${references[@]}"; do
  if [[ ! "${reference}" =~ @sha256:[a-f0-9]{64}$ ]]; then
    printf 'image-pin verification: reference is not digest-pinned: %s\n' "${reference}" >&2
    exit 1
  fi

  printf 'Verifying %s\n' "${reference}"
  docker manifest inspect "${reference}" >/dev/null
done
