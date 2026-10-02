package com.automata.shared

import kotlinx.serialization.Serializable

/**
 * Resultado tipado para la comunicación con adaptadores externos (Shopify, Meta, etc.).
 * Gobierna las decisiones de reintento, cola DLQ y degradación de canal (Principio v5.3).
 */
@Serializable
sealed interface PortResult<out T> {
    @Serializable
    data class Ok<T>(val value: T) : PortResult<T>

    @Serializable
    sealed interface Failure : PortResult<Nothing>

    @Serializable
    data class Transient(val cause: String) : Failure

    @Serializable
    data class RateLimited(val retryAfterSeconds: Long) : Failure

    @Serializable
    data class Permanent(val code: String, val detail: String) : Failure

    @Serializable
    data object AuthExpired : Failure
}
