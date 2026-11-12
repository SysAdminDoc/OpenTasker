package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.VariableNamePolicy

/**
 * Tasker's Variable Clear. Args: `name`, `pattern`, `local_only`, `clear_all`. The name is read as
 * written, never filled in. A plain name is cleared for the running task the way Reset Return
 * Variable clears it. With `pattern`, every variable whose name matches goes (Tasker's Matches, so
 * `%item*` or `%a/%b`), globals too unless `local_only`. No name clears every local, and every
 * global as well with `clear_all`. Both switches only count where Tasker reads them. The array a
 * step like Split Text keeps under a cleared name goes with it.
 */
class VarClearAction : DeclaredAction(ActionCatalog.require("var.clear")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val cleared = ctx.variables.clearVariables(args)
            ?: return ActionResult.Failure("invalid variable name '${args["name"].orEmpty().trim()}'")
        ctx.logger("var.clear -> $cleared variable(s)")
        return ActionResult.Success
    }
}

/** What [VarClearAction] clears, shared with the preview. Returns how many, or null for a name that isn't a variable. */
internal fun VariableStore.clearVariables(args: Map<String, String>): Int? {
    val name = args["name"]?.trim().orEmpty()
    fun flag(key: String) = args[key]?.toBooleanStrictOrNull() == true
    return when {
        name.isEmpty() -> clearMatching(pattern = null, includeGlobals = flag("clear_all"))
        flag("pattern") -> clearMatching(name, includeGlobals = !flag("local_only"))
        else -> {
            val variable = VariableNamePolicy.normalize(name) ?: return null
            val wasSet = get(variable) != null
            clearForTask(variable)
            val hadArray = removeArray(variable)
            if (wasSet || hadArray) 1 else 0
        }
    }
}
