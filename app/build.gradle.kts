plugins { id("com.android.application") }

val isolatedRuntimeCheck = providers.gradleProperty("lexiflow.isolatedRuntimeCheck").orNull == "true"
val researchProfile = providers.gradleProperty("lexiflow.research").orNull == "true"
require(!researchProfile || !isolatedRuntimeCheck) { "Research and runtimecheck profiles are separate" }
val releaseDictionary = providers.gradleProperty("lexiflow.releaseDictionary").orNull
val releaseDictionaryHash = providers.gradleProperty("lexiflow.approvedManifestSha256").orNull
require((releaseDictionary == null) == (releaseDictionaryHash == null)) {
    "Pass both lexiflow.releaseDictionary and lexiflow.approvedManifestSha256"
}
require(releaseDictionaryHash == null || releaseDictionaryHash.matches(Regex("[0-9a-f]{64}"))) {
    "lexiflow.approvedManifestSha256 must be a lowercase SHA-256"
}
val generatedReleaseAssets = layout.buildDirectory.dir("generated/approved-dictionary/${releaseDictionaryHash ?: "unused"}")
if (releaseDictionary != null) {
    val source = rootProject.file(releaseDictionary)
    val pipeline = rootProject.file("research/prototypes/sqlite_poc")
    val prepareDictionary = tasks.register<Exec>("prepareApprovedDictionary") {
        inputs.files(source.resolve("manifest.json"), source.resolve("dictionary.sqlite"))
        inputs.files(rootProject.fileTree(pipeline) { include("*.py", "schema.sql") })
        inputs.property("approvedManifestSha256", releaseDictionaryHash!!)
        outputs.dir(generatedReleaseAssets)
        commandLine(providers.gradleProperty("lexiflow.python").getOrElse("python"),
            pipeline.resolve("prepare_release_asset.py"), source, generatedReleaseAssets.get().asFile,
            releaseDictionaryHash)
    }
    tasks.configureEach {
        if (name == "mergeReleaseAssets") dependsOn(prepareDictionary)
    }
}
if (isolatedRuntimeCheck) {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".gradle/runtimecheck-build/app"))
} else if (researchProfile) {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".gradle/research-build/app"))
}

android {
    namespace = "app.lexiflow"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "app.lexiflow"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                argument("room.schemaLocation", project.file("schemas").path)
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (isolatedRuntimeCheck) {
        buildTypes.getByName("debug").applicationIdSuffix = ".runtimecheck"
    }
    val debug = sourceSets.getByName("debug")
    if (researchProfile) {
        buildTypes.getByName("debug").applicationIdSuffix = ".research"
        debug.res.srcDir("src/researchProfile/res")
        val researchAssets = rootProject.file("research/data/processed/research-app")
        val dictionary = researchAssets.resolve("dictionary-research")
        val expectedPair = setOf("manifest.json", "dictionary.sqlite")
        require(researchAssets.list()?.toSet() == setOf("dictionary-research")
                && dictionary.list()?.toSet() == expectedPair
                && expectedPair.all { dictionary.resolve(it).isFile }) {
            "Generate the exact dictionary-research pair before selecting the research profile"
        }
        debug.assets.srcDir(researchAssets)
        val validator = rootProject.file("research/prototypes/sqlite_poc/research_package.py")
        val verifyResearch = tasks.register<Exec>("verifyResearchDictionary") {
            commandLine(providers.gradleProperty("lexiflow.python").getOrElse("python"), validator, "validate", dictionary)
        }
        tasks.configureEach {
            if (name == "mergeDebugAssets") dependsOn(verifyResearch)
        }
    } else {
        debug.assets.srcDir(rootProject.file("research/data/processed/android-debug-assets"))
    }
    if (isolatedRuntimeCheck && providers.gradleProperty("lexiflow.designPreview").orNull == "true") {
        sourceSets.getByName("debug").assets.srcDir(rootProject.file("research/data/processed/design-preview"))
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("research/data/processed/android-test-assets"))
    if (isolatedRuntimeCheck && providers.gradleProperty("lexiflow.capacityFixture").orNull == "true") {
        sourceSets.getByName("androidTest").assets.srcDir(rootProject.file(".gradle/benchmark-capacity-assets"))
    }
    if (releaseDictionary != null) {
        sourceSets.getByName("release").assets.srcDir(generatedReleaseAssets.get().asFile)
    }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2")
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
