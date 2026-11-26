// Type-checks the Compose UI sources against JetBrains Compose for Desktop (same androidx.compose API surface,
// published on Maven Central) plus android-all for framework types. Android-only APIs are stubbed in stubs/.
plugins { kotlin("jvm") version "2.1.21"; kotlin("plugin.compose") version "2.1.21"; kotlin("plugin.serialization") version "2.1.21" }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets["main"].kotlin.srcDirs("stubs", "build/generated/src")
val cmp = "1.7.3"
// Android libraries ship as AARs; extract their classes.jar for compile-only use.
val aar by configurations.creating { isTransitive = false }
val aarJars = layout.buildDirectory.dir("aar-jars")
val extractAars by tasks.registering {
    inputs.files(aar); outputs.dir(aarJars)
    doLast {
        val out = aarJars.get().asFile; out.mkdirs()
        aar.files.forEach { f -> copy { from(zipTree(f)) { include("classes.jar") }; into(out); rename { f.nameWithoutExtension + ".jar" } } }
    }
}
dependencies {
    listOf("api", "provider", "shared", "aidl").forEach { aar("dev.rikka.shizuku:$it:13.1.5@aar") }
    compileOnly(files(aarJars.map { d -> d.asFile.listFiles()?.toList().orEmpty() }).builtBy(extractAars))
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
    implementation("studio.virgule.pol:pol-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.compose.runtime:runtime-desktop:$cmp")
    implementation("org.jetbrains.compose.ui:ui-desktop:$cmp")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$cmp")
    implementation("org.jetbrains.compose.material3:material3-desktop:$cmp")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:$cmp")
}

// Copies every Android source file (all flavors) into one source set and generates an R stub.
val prepare by tasks.registering {
    val app = file("../../android/app/src")
    val out = layout.buildDirectory.dir("generated/src")
    inputs.dir(app); outputs.dir(out)
    doLast {
        val dir = out.get().asFile; dir.deleteRecursively(); dir.mkdirs()
        val names = sortedMapOf<String, MutableSet<String>>()
        app.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            f.copyTo(dir.resolve(f.parentFile.name + "_" + f.name))
            Regex("""R\.(string|drawable)\.(\w+)""").findAll(f.readText()).forEach { names.getOrPut(it.groupValues[1]) { sortedSetOf() } += it.groupValues[2] }
        }
        dir.resolve("R.kt").writeText(buildString {
            append("package studio.virgule.pol.android\nobject R {\n")
            names.forEach { (k, v) -> append("  object $k {\n"); v.forEach { append("    const val $it = 0\n") }; append("  }\n") }
            append("}\n")
        })
    }
}
tasks.named("compileKotlin") { dependsOn(prepare) }
