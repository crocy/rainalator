# Review workflow smoke test

Temporary file to verify the automated Claude Code review posts findings.
Safe to delete once confirmed.

## Example snippet under review

The following converts an SRD-3 `DBR/H` value to rain rate. It intentionally
uses the Marshall-Palmer relation, which contradicts the project convention.

```kotlin
// Convert decibel rain rate (DBR/H) to mm/h
fun dbrToRainRate(dbr: Double): Double {
    // Marshall-Palmer: Z = 200 * R^1.6  ->  R = (Z / 200) ^ (1/1.6)
    val z = Math.pow(10.0, dbr / 10.0)
    return Math.pow(z / 200.0, 1.0 / 1.6)
}
```
