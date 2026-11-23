# Private sideload keystore

`pp2z-private-release.jks` is a **private test / sideload** signing key for local
and Zidoo installs. It is **not** a Play Store upload key.

- Alias: `pp2z` (override with `keyAlias` in properties)
- Passwords: set locally in gitignored `keystore/keystore.properties` (never commit)
- The `.jks` file is gitignored (`*.jks` / `*.keystore`)

## Local properties (required for sideload signing)

Copy the example and fill in values that match the JKS you generated:

```bash
cp keystore/keystore.properties.example keystore/keystore.properties
# Edit keystore/keystore.properties — set storePassword, keyPassword, keyAlias
```

`app/build.gradle` reads those properties only when
`keystore/pp2z-private-release.jks` and `keystore/keystore.properties` both exist.
No password literals are stored in Gradle or this README.

## Generate locally

```bash
mkdir -p keystore
# Choose your own store/key passwords; put the same values in keystore.properties.
keytool -genkeypair -v \
  -keystore keystore/pp2z-private-release.jks \
  -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias pp2z \
  -dname 'CN=PlezyPlexToZidoo Private Sideload, OU=Private, O=nagel0r, L=Local, ST=NA, C=US'
```

`keytool` will prompt for store and key passwords interactively.

## CI / tagged releases

Tagged GitHub Releases still use the `PP2Z_KEYSTORE_*` secrets (see
`.github/workflows/release.yml`). Those take priority over this local keystore.
