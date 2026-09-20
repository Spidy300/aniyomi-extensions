ext {
    extName = "NikaStream"
    extClass = ".NikaStream"
    // Bump this every time you publish an update.
    extVersionCode = 1
    isNsfw = false
}

// NOTE: this module is meant to be dropped into the official
// aniyomiorg/aniyomi-extensions repo (or a fork of it) under:
//   src/all/nikastream/
// That repo's root build.gradle + buildSrc provide `extName`, `extClass`,
// the `lib-multisrc`/`core` dependencies, AnimeHttpSource, etc. It is not
// meant to build standalone with just this file — see SETUP.md.

apply(from = "$rootDir/common.gradle")
