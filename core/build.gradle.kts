plugins { kotlin("jvm") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("dnsjava:dnsjava:3.6.3")
    testImplementation("junit:junit:4.13.2")
}
