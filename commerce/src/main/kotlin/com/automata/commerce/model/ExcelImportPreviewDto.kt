package com.automata.commerce.model

import kotlinx.serialization.Serializable

@Serializable
data class FieldChange(
    val fieldName: String,
    val oldValue: String,
    val newValue: String
)

@Serializable
data class PreviewItem(
    val key: String,
    val item: String,
    val status: String,
    val category: String,
    val changes: List<FieldChange> = emptyList(),
    val reason: String? = null
)

@Serializable
data class ExcelPreviewReport(
    val token: String,
    val hashArchivo: String,
    val creados: List<PreviewItem>,
    val actualizados: List<PreviewItem>,
    val sinCambios: List<PreviewItem>,
    val noTocados: List<PreviewItem>,
    val bloqueados: List<PreviewItem>,
    val fileError: String? = null,
    val formatErrors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val canConfirm: Boolean,
    val alreadyImported: Boolean = false
)
