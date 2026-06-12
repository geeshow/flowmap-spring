plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // K1 frontend: PSI + BindingContext + ConstantExpressionEvaluator.
    // Fully on Maven Central — no JetBrains -for-ide artifacts required.
    // NOTE: kotlin-compiler-embeddable SHADES IntelliJ classes to the
    // `org.jetbrains.kotlin.com.intellij.*` package; the resolver code imports
    // PsiElement/Disposer from there (not plain `com.intellij.*`).
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")

    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    // Spring annotation/base-type jars placed on the analysis classpath so the K1
    // front-end can RESOLVE @Service/@RestController/@Repository, @*Mapping paths,
    // @FeignClient/@HttpExchange, Spring Data base interfaces, and Batch types.
    // Only their type signatures are needed (short names + annotation args); the
    // analyzed project's own dependencies stay unresolved by design.
    implementation("org.springframework:spring-context:6.1.13")
    implementation("org.springframework:spring-web:6.1.13")
    implementation("org.springframework:spring-jdbc:6.1.13")
    implementation("org.springframework.data:spring-data-jpa:3.2.10")
    implementation("org.springframework.cloud:spring-cloud-openfeign-core:4.1.3")
    implementation("org.springframework.batch:spring-batch-core:5.1.2")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.flowmap.callgraph.CliKt")
}

tasks.test {
    useJUnitPlatform()
}
