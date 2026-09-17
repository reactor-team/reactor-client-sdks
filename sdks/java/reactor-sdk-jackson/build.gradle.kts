plugins {
    id("reactor-java-conventions")
}

description =
    "Optional interop between JsonValue and Jackson's JsonNode. Separate from the core so that " +
        "applications choose their own Jackson line — there are two in use, under different " +
        "group ids — and applications that use neither pay nothing."

dependencies {
    api(project(":reactor-sdk"))
    // 2.x rather than tools.jackson 3.x because 2.x is still what most applications are on. An
    // application on 3.x writes the same twenty lines this module holds, against its own line;
    // that is exactly why neither one is in the core's public signatures.
    api("com.fasterxml.jackson.core:jackson-databind:2.22.2")
}
