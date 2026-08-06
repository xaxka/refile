plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    jacoco
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
}

jacoco {
    toolVersion = "0.8.12"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.dav4jvm)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.logging)
}

// OpenList 后端（xa.refile.core.openlist 包）要求 ≥97% 行覆盖。
// 仅对该包做覆盖率校验（既有 WebDAV/TMDB 等代码不在本次校验范围），
// 并排除 kotlinx.serialization 生成的 $serializer 类（无手写逻辑）。
tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/*\$\$serializer*",
                        "**/*\$serializer*",
                    )
                }
            },
        ),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            // 仅校验 OpenList 实现（客户端 + 认证拦截器 + 异常类）。
            includes = listOf(
                "xa.refile.core.openlist.OpenListClient*",
                "xa.refile.core.openlist.OpenListAuthInterceptor*",
                "xa.refile.core.openlist.OpenListException*",
            )
            limit {
                counter = "LINE"
                minimum = "0.97".toBigDecimal()
            }
        }
    }
}

tasks.register("openlistCoverageCheck") {
    group = "verification"
    description = "运行 OpenList 测试并校验 ≥97% 行覆盖。"
    dependsOn(tasks.jacocoTestCoverageVerification)
}

