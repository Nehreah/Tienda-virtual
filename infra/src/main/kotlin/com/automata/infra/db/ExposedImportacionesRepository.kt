package com.automata.infra.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

class ExposedImportacionesRepository {

    private val logger = LoggerFactory.getLogger(ExposedImportacionesRepository::class.java)

    fun isHashImported(hash: String, modo: String = "IMPORT_NEW"): Boolean = transaction {
        ImportacionesTable
            .select {
                (ImportacionesTable.hashArchivo eq hash) and
                (ImportacionesTable.modo eq modo) and
                (ImportacionesTable.estado eq "COMPLETED")
            }
            .count() > 0
    }

    fun recordImport(
        id: String,
        usuario: String,
        hash: String,
        modo: String = "IMPORT_NEW",
        estado: String,
        conteosJson: String? = null,
        erroresJson: String? = null
    ) {
        try {
            transaction {
                ImportacionesTable.insert { row ->
                    row[ImportacionesTable.id] = id
                    row[ImportacionesTable.usuario] = usuario
                    row[ImportacionesTable.hashArchivo] = hash
                    row[ImportacionesTable.modo] = modo
                    row[ImportacionesTable.estado] = estado
                    row[ImportacionesTable.fecha] = Instant.now()
                    row[ImportacionesTable.conteos] = conteosJson ?: "{}"
                    row[ImportacionesTable.errores] = erroresJson ?: "[]"
                }
            }
        } catch (e: Exception) {
            logger.error("Error registrando importación $id: ${e.message}", e)
        }
    }
}
