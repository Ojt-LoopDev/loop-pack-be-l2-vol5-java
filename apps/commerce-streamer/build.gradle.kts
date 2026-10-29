plugins {
    checkstyle
}

// commerce-api와 같은 검사(docs/week6/decisions.md D-24) — 이번 주에 streamer에 계층이 처음 생긴다.
checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // 구매 활동 소비 테스트용 broker (docs/week6/decisions.md D-24) — 버전은 Spring Boot가 관리한다
    testImplementation("org.testcontainers:kafka")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

// api와 streamer가 같은 계약 JSON을 읽는다(docs/week6/decisions.md D-24). 테스트는 이 경로로 파일을 찾는다.
tasks.test {
    systemProperty("contract.dir", rootProject.file("docs/week6/contract").absolutePath)
}
