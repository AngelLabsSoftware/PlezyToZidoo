# Private sideload keystore

`pp2z-private-release.jks` is a **private test / sideload** signing key for local
and Zidoo installs. It is **not** a Play Store upload key.

- Alias: `pp2z`
- Passwords: see `app/build.gradle` (`signingConfigs.release` local fallback)
- The `.jks` file is gitignored (`*.jks` / `*.keystore`)

## Generate locally

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/pp2z-private-release.jks \
  -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias pp2z \
  -storepass 'pp2z-private-sideload' \
  -keypass 'pp2z-private-sideload' \
  -dname 'CN=PlezyPlexToZidoo Private Sideload, OU=Private, O=nagel0r, L=Local, ST=NA, C=US'
```

## CI / tagged releases

Tagged GitHub Releases still use the `PP2Z_KEYSTORE_*` secrets (see
`.github/workflows/release.yml`). Those take priority over this local keystore.
