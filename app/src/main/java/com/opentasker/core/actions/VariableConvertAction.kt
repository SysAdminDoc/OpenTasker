package com.opentasker.core.actions

import android.text.Html
import com.opentasker.core.data.VariableConvert
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.VariableNamePolicy

/**
 * Tasker's Variable Convert. Args: `name`, `function` (one of [VariableConvert.FUNCTIONS]), `var`,
 * `url_safe`. Converts %name's value and stores it in `var`, or back in %name when `var` is blank.
 * The result keeps %name's secret flag, since a digest or a decode of a secret is still one.
 */
class VarConvertAction(
    private val htmlToText: (String) -> String = { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() },
) : DeclaredAction(ActionCatalog.require("var.convert")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.let(VariableNamePolicy::normalize) ?: return ActionResult.Failure("invalid variable name")
        val target = args["var"].orEmpty().ifBlank { name }.let(VariableNamePolicy::normalize)
            ?: return ActionResult.Failure("invalid variable name to store the result in")
        val function = args["function"]?.trim().orEmpty()
        if (function !in VariableConvert.FUNCTIONS) return ActionResult.Failure("unknown conversion '$function'")
        val value = ctx.variables.get(name) ?: return ActionResult.Failure("%$name has no value")
        val urlSafe = args["url_safe"]?.toBooleanStrictOrNull() == true
        val converted = VariableConvert.convert(function, value, htmlToText, urlSafe)
            ?: return ActionResult.Failure("%$name doesn't hold something $function can convert")
        ctx.variables.set(target, converted, sensitive = ctx.variables.isSensitive(name))
        ctx.logger("var.convert $function -> %$target")
        return ActionResult.Success
    }
}
