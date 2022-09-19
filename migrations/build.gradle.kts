buildscript {
    dependencies {
        classpath(libs.postgres)
    }
}

plugins {
    kotlin("jvm")
    id("org.flywaydb.flyway")
}

java.sourceCompatibility = JavaVersion.VERSION_11
java.targetCompatibility = JavaVersion.VERSION_11

val postgresHost = System.getenv("DB_HOST") ?: "127.0.0.1"
val postgresPort = System.getenv("DB_PORT") ?: "5432"

flyway {
    url = "jdbc:postgresql://$postgresHost:$postgresPort/object-store-gateway"
    driver = "org.postgresql.Driver"
    user = "postgres"
    password = "password1"
    schemas = arrayOf("object-store-gateway")
    locations = arrayOf("filesystem:$projectDir/src/main/resources/db/migration", "db/migration")
}
