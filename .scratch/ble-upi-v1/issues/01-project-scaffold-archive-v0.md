# 01 — Project scaffold + archive v0 prototype

**What to build:** A clean multi-module Android Gradle project where `:merchant-app`, `:customer-app`, and `:protocol-sdk` all compile green with zero errors. The v0 prototype code and all dead infrastructure are moved to `archive/v0/` so they can't be mistaken for a starting point. Test frameworks configured across all modules ready for TDD.

**Blocked by:** None — can start immediately

**Status:** completed

- [ ] Restructure `android/` from single-module `:app` (rootProject `BLEChat`) to multi-module with `rootProject.name = "BLE-UPI"` and three included modules in `settings.gradle.kts`
- [ ] `:protocol-sdk` builds as a Kotlin library module with `compileSdk 34`, `minSdk 26`, target `34`, zero external dependencies
- [ ] `:merchant-app` and `:customer-app` build as Android application modules, both depending on `:protocol-sdk`
- [ ] JUnit 5, MockK, and AndroidX Test (Espresso) dependencies declared in each module that needs them
- [ ] Gradle wrapper updated if needed, all modules share a common version catalog or consistent dependency versions
- [ ] All v0 prototype source files moved to `archive/v0/` and deleted from the active `android/` source tree
- [ ] Old Gradle module configs, Python v0 scripts, and v0 `index.html` moved to `archive/v0/` or deleted
- [ ] Old v0 Service UUID purged from all active code, Python files, and docs
- [ ] `./gradlew assembleDebug` succeeds for all three modules from a clean clone
- [ ] `.gitignore` covers all build outputs, `.gradle/`, and generated files for the new multi-module layout
