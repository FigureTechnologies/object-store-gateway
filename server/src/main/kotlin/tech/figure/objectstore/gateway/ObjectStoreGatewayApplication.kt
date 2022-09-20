package tech.figure.objectstore.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import tech.figure.objectstore.gateway.configuration.DatabaseProperties
import tech.figure.objectstore.gateway.configuration.EventStreamProperties
import tech.figure.objectstore.gateway.configuration.ObjectStoreProperties
import tech.figure.objectstore.gateway.configuration.ProvenanceProperties

@SpringBootApplication
@EnableConfigurationProperties(
    value = [
        EventStreamProperties::class,
        ObjectStoreProperties::class,
        ProvenanceProperties::class,
        DatabaseProperties::class,
    ]
)
@EnableScheduling
class ObjectStoreGatewayApplication

fun main(args: Array<String>) {
    runApplication<ObjectStoreGatewayApplication>(*args)
}
