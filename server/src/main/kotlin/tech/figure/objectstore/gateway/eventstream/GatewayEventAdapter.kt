package tech.figure.objectstore.gateway.eventstream

import mu.KotlinLogging
import tech.figure.block.api.proto.BlockOuterClass.Attribute
import tech.figure.block.api.proto.BlockOuterClass.TxEvent

/**
 * A base adapter class for a given TxEvent, consumed via the event stream libraries.  This allows an event to be
 * processed by an implementation, and for its expected values to be assigned to class variables reactively based on the
 * contents.
 *
 * @param sourceEvent The event encountered by the event-stream library that may or may not contain expected values in
 * its attributes.
 */
abstract class GatewayEventAdapter(val sourceEvent: TxEvent) {
    /**
     * Lazily-parsed tx events, accessible for event value fetching.
     */
    private val attributeMap: Map<String, Attribute> by lazy { sourceEvent.attributesList.associateBy { it.key } }

    /**
     * On-demand access to the source event's txHash value for simpler syntax throughout the application.
     */
    val txHash: String by lazy { sourceEvent.txHash }

    /**
     * Fetches the specific key from the event, if present, as a String.  If not, null is returned.
     *
     * @param key The attribute's unique name, corresponding to an expected output value.
     */
    protected fun getEventStringValueOrNull(key: String): String? = attributeMap[key]?.value

    /**
     * Fetches the specific key from the event, if present, as a String.  If not, an exception is thrown indicating the
     * Provenance Blockchain event's transaction hash.
     *
     * @param key The attribute's unique name, corresponding to an expected output value.
     */
    protected fun getEventStringValue(key: String): String = getEventStringValueOrNull(key)
        ?: error("Could not get String value [$key] for event [${sourceEvent.txHash}]")

    /**
     * Fetches the specific key from the event, if present, as a String, transforming into the specified type.  If not,
     * null is returned and a warning indicating the reason it could not be transformed is logged.
     *
     * @param key The attribute's unique name, corresponding to an expected output value.
     * @param transform A dynamic function that defines how to convert the attribute's String value to the specified T.
     */
    protected inline fun <reified T> getEventValueOrNull(key: String, transform: (String) -> T): T? = try {
        getEventStringValueOrNull(key)?.let(transform)
    } catch (e: Exception) {
        KotlinLogging.logger {}.warn("Failed to convert derived value for event key [$key] to type [${T::class.qualifiedName}]", e)
        null
    }

    /**
     * Fetches the specific key from the event, if present, as a String, transforming into the specified type.  If not,
     * an exception is thrown indicating the reason it could not be transformed.
     *
     * @param key The attribute's unique name, corresponding to an expected output value.
     * @param transform A Dynamic function that defines how to convert the attribute's String value to the specified T.
     */
    protected inline fun <reified T> getEventValue(key: String, transform: (String) -> T): T = getEventValueOrNull(
        key = key,
        transform = transform,
    ) ?: error("Could not get key [$key] for event [${sourceEvent.txHash}]")
}
