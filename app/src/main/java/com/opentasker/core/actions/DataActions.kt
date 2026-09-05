package com.opentasker.core.actions

import com.opentasker.core.data.StructuredDataReader
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Parse a JSON / CSV / XML / HTML string into variables, fully on-device.
 *
 * Args:
 *   - "source": the data to parse (typically a `%var` holding an HTTP response or file contents)
 *   - "format": "json" (default), "csv", "xml", or "html"
 *   - "path": selector — JSON `items[0].name`, CSV column `c` or cell `r,c`, XML `root/item/name`, or HTML CSS selector
 *   - "var": output variable base name (default "data")
 *
 * Sets `%var` to the first extracted value, stores all values as the `var` array (`%var(#)` /
 * `%var(0)` / `%var()`), and sets `%var_count` to the number of values.
 */
class DataReadAction : DeclaredAction(ActionCatalog.require("data.read")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val format = args["format"]?.trim()?.ifBlank { null } ?: "json"
        val path = args["path"].orEmpty()
        val varName = (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: "data"

        // The reader refuses these too, but it can only fail closed with a null. Naming the reason
        // here is the difference between "your selector is unsupported and here is why" and the
        // generic "could not read" a user would otherwise get for a selector that looks valid.
        if (format.trim().lowercase() == "html") {
            StructuredDataReader.unsupportedHtmlSelectorReason(path.trim())
                ?.let { return ActionResult.Failure(it) }
        }

        val result = StructuredDataReader.read(format, source, path)
            ?: return ActionResult.Failure("could not read $format data at path '$path'")

        ctx.variables.set(varName, result.values.firstOrNull() ?: "")
        ctx.variables.setArray(varName, result.values)
        ctx.variables.set("${varName}_count", result.values.size.toString())
        ctx.logger("data.read: $format -> \$$varName (${result.values.size} value(s))")
        return ActionResult.Success
    }
}
