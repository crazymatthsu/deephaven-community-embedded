package io.github.cmh.dhc.embed.spring;

import io.deephaven.engine.table.Table;
import io.github.cmh.dhc.embed.EmbeddedDeephaven;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /dhc/tables}: the tables the application scripts have published, with their size
 * and columns. Off unless {@code dhc.tables.endpoint=true} ({@code DHC_TABLES_ENDPOINT}), because
 * it tells whoever can reach the application's port what is inside.
 */
@RestController
public class TablesEndpoint {

    private final EmbeddedDeephaven deephaven;

    public TablesEndpoint(EmbeddedDeephaven deephaven) {
        this.deephaven = deephaven;
    }

    @GetMapping("/dhc/tables")
    public List<Map<String, Object>> tables() {
        Map<String, Table> tables = deephaven.tables();
        return deephaven.engine().call(() -> {
            List<Map<String, Object>> listing = new ArrayList<>();
            tables.forEach((name, table) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("rows", table.size());
                entry.put("ticking", table.isRefreshing());
                entry.put("columns", table.getDefinition().getColumnNames());
                listing.add(entry);
            });
            return listing;
        });
    }
}
