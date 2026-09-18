plugins {
    id("reactor-kotlin-conventions")
    id("reactor-maven-central")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

description =
    "An idiomatic Kotlin facade over the Reactor SDK: suspend functions, flows for the control " +
        "events, and a builder DSL. A facade, not a second binding — it holds no native symbol " +
        "and adds no path to the FFI boundary."

dependencies {
    api(project(":reactor-sdk"))
    // await() over CompletableFuture, callbackFlow, and the CoroutineDispatcher a Dispatcher is
    // adapted from. Nothing else is needed: everything this module does is turn one shape into
    // another.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // The same fake library the core's own tests bind to. A second copy here would be a second
    // thing to keep in step with the FFI, which is what this module exists not to be.
    testImplementation(testFixtures(project(":reactor-sdk")))
}

kotlin {
    // Every public declaration states its visibility and its return type. On a module whose whole
    // job is a published API, inferring either is how a type nobody meant to expose becomes part of
    // the contract. Here rather than in the conventions: the example module is not an API.
    explicitApi()
}

// No module-info.java. Kotlin does not compile one, and the usual workaround — a second source set
// compiled by javac against the Kotlin output — buys an automatic module name this module can
// declare far more cheaply. reactor-sdk-jackson is unnamed for its own reasons; this one is
// explicit about it.
tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to "inc.reactor.sdk.kotlin")
    }
}

// Applied to this module alone rather than to the whole build: it is the only one whose public
// surface is Kotlin, and the Java modules already have javac and doclint watching theirs.
//
// The dumped ABI lives at api/reactor-sdk-kotlin.api and is reviewed like any other file. A change
// to it in a diff is the question "did you mean to change the published surface?", asked where
// someone can answer it — `explicitApi()` above stops a declaration becoming public by accident,
// and this stops one becoming public without anyone noticing.
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            pom { standardPom("Reactor SDK for Kotlin", project.description ?: "") }
        }
    }
}
