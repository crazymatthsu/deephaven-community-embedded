plugins {
    id("dhe.deephaven-library")
}

description = "The demo's domain: ExecEvent, OrderCache, the mock generator, table definitions, the oracle."

dependencies {
    // Table and TableDefinition, for the table definitions. Nothing of the server.
    api(libs.deephaven.engine.api)
}
