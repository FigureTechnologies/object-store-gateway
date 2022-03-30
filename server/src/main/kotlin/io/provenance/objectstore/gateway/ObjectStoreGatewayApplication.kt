package io.provenance.objectstore.gateway

import io.provenance.objectstore.gateway.configuration.ContractProperties
import io.provenance.objectstore.gateway.configuration.DatabaseProperties
import io.provenance.objectstore.gateway.configuration.EventStreamProperties
import io.provenance.objectstore.gateway.configuration.ObjectStoreProperties
import io.provenance.objectstore.gateway.configuration.ProvenanceProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableConfigurationProperties(value = [
	EventStreamProperties::class,
	ObjectStoreProperties::class,
	ProvenanceProperties::class,
	ContractProperties::class,
	DatabaseProperties::class,
])
@EnableScheduling
class ObjectStoreGatewayApplication

fun main(args: Array<String>) {
	runApplication<ObjectStoreGatewayApplication>(*args)
}
