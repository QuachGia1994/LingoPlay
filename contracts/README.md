# LingoPlay product contract

`product-contract.json` is the cross-platform business contract for values that must remain identical on Android and iOS while both clients stay fully native.

The contract intentionally covers only product semantics that are dangerous to duplicate by hand:

- supported source/target language codes,
- playback-speed choices,
- dubbing-mode duck floor, dub gain and fade duration,
- Google Play / StoreKit Plus product identifiers plus Stage 21 server-authority/fail-closed verification semantics,
- Clean Background opt-in/model/runtime semantics and whether cross-device verification is complete.

Runtime code remains Kotlin/Compose and Swift/SwiftUI. `scripts/verify_product_contract.py` compares both native implementations against this file and fails CI on drift. This is a parity guard, not a shared-runtime or code-generation layer.
