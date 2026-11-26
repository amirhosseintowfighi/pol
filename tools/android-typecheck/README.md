# Android type-check (no Android SDK needed)

Compiles every Kotlin source of `android/app` (gateway and client flavors, Compose UI included) against:

- Robolectric's `android-all` jar (Android 16 framework classes), compile-only;
- JetBrains Compose for Desktop 1.7.3 (same `androidx.compose` API surface as Jetpack Compose, on Maven Central);
- small stubs in `stubs/` for the few Android-only APIs (`ComponentActivity`, `setContent`, activity-result
  contracts, `LocalContext`, `stringResource`, dynamic color).

It catches type errors, wrong API names and signature mismatches. It does **not** replace a real Android build:
no resource linking, manifest merge, R8, lint, or running the app. Use it where the Android SDK is unavailable:

```bash
cd tools/android-typecheck && gradle compileKotlin
```
