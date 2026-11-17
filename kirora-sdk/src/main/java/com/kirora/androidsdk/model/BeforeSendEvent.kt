package com.kirora.androidsdk.model

/**
 * What [KiroraOptions.beforeSend] receives and returns: a detached copy of a behavioural event,
 * made only of JSON values (maps, lists, strings, finite numbers, booleans and null).
 *
 * Return it with fields redacted or removed, or null to drop the event. The SDK keeps the
 * original [name], and its own owner, time and idempotency key, whatever is returned.
 */
public class BeforeSendEvent(
    public val name: String,
    public val properties: Map<String, Any?>,
    public val context: Map<String, Any?>
) {
    /** A copy with other properties and/or context. */
    @JvmOverloads
    public fun copy(
        properties: Map<String, Any?> = this.properties,
        context: Map<String, Any?> = this.context
    ): BeforeSendEvent = BeforeSendEvent(name, properties, context)
}
