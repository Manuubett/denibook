# Deni Book (com.manuubett.denibook, appId `denibook`)

Customer debts and payments for small hotels (eateries), shops and kiosks.

## Set up
1. New private GitHub repo. Upload everything in this folder (keep `.github`, `app`, `settings.gradle.kts`, `gradle.properties` at the top level).
2. Actions > "Create signing key (run once)". Add the 4 secrets (paste only the long line under KEYSTORE_BASE64).
3. Push. Download the APK from the build.
4. Payments admin: add app `denibook`. Store admin: upload as "Deni Book".

## Notes
- Records are on the phone (SQLite). Backup: JSON file saved with the system file picker, no storage permission needed.
- Owner PIN sees everything. Waiter PIN adds customers and debts only.
- Wrong entries are cancelled, never erased.
