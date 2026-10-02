#!/usr/bin/env bash
# Generates an RS256 key pair for signing access tokens.
#   ./scripts/generate-jwt-keys.sh [output-dir]   (default: ./secrets, git-ignored)
# Then set JWT_PRIVATE_KEY_LOCATION / JWT_PUBLIC_KEY_LOCATION (see .env.example).
# In shared environments, store the private key in your secret manager, never in the repo.
set -euo pipefail

out_dir="${1:-secrets}"
mkdir -p "$out_dir"
umask 077

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$out_dir/jwt-private.pem"
openssl pkey -in "$out_dir/jwt-private.pem" -pubout -out "$out_dir/jwt-public.pem"

echo "Wrote $out_dir/jwt-private.pem and $out_dir/jwt-public.pem"
echo "JWT_PRIVATE_KEY_LOCATION=file:./$out_dir/jwt-private.pem"
echo "JWT_PUBLIC_KEY_LOCATION=file:./$out_dir/jwt-public.pem"
