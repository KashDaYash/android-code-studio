#!/bin/bash

# Generate a new Android signing keystore
# This script creates a signing-key.jks file that can be used to sign Android APKs

KEYSTORE_FILE="signing/signing-key.jks"
KEYSTORE_PASSWORD="AndroidCodeStudio2024!"
KEY_ALIAS="androidcs"
KEY_PASSWORD="AndroidCodeStudio2024!"
VALIDITY=10950  # 30 years

# Create signing directory if it doesn't exist
mkdir -p signing

# Generate keystore
keytool -genkey -v \
  -keystore "$KEYSTORE_FILE" \
  -keyalg RSA \
  -keysize 2048 \
  -validity $VALIDITY \
  -alias "$KEY_ALIAS" \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=Android Code Studio, OU=Development, O=AndroidCodeStudio, C=IN"

echo ""
echo "✅ Keystore generated successfully!"
echo "📁 Keystore file: $KEYSTORE_FILE"
echo "🔑 Keystore password: $KEYSTORE_PASSWORD"
echo "🔑 Key alias: $KEY_ALIAS"
echo "🔑 Key password: $KEY_PASSWORD"
echo ""
echo "⚠️  IMPORTANT: Save these credentials securely!"
echo "⚠️  Add the keystore file to .gitignore if not already done"
