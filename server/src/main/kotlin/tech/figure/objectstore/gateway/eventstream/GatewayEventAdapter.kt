package tech.figure.objectstore.gateway.eventstream

import mu.KotlinLogging

/**
 * A base adapter class for a given Transaction Event, consumed via an event stream library.  This allows an event to be
 * processed by an implementation, and for its expected values to be assigned to class variables reactively based on the
 * contents.
 */
abstract class GatewayEventAdapter(
    /**
     * Lazily-parsed tx events, accessible for event value fetching.
     */
    private val attributeMap: Map<String, String>,
    /**
     * On-demand access to the source event's txHash value for simpler syntax throughout the application.
     */
    val txHash: String,
    /**
     * The Provenance Event Type
     */
    val eventType: String,
) {
    /**
     * Fetches the specific key from the event, if present, as a String.  If not, null is returned.
     *
     * @param key The attribute's unique name, corresponding to an expected output value.
     */
    protected fun getEventStringValueOrNull(key: String): String? = attributeMap[key]

    /**
     * Fetches the specific key from the event, if present, as a String.  If not, an exception is thrown indicating the
     * Provenance Blockchain event's transaction hash.
     *
     * @param key The attribute's unique name, corresponding to an expected output value.
     */
    protected fun getEventStringValue(key: String): String = getEventStringValueOrNull(key)
        ?: error("Could not get String value [$key] for event [$txHash]")

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
    ) ?: error("Could not get key [$key] for event [$txHash]")
}
