package app.readly.input.sites

import org.jsoup.nodes.Document

/**
 * Runtime executor for imported [CleanupOp] lists (the extension's `cs_code`
 * primitives, interpreted declaratively: no scripts, no styles, no network).
 *
 * Grouping is explicit: ops sharing one [CleanupOp.groupId] came from one
 * upstream `cond`, and the executor selects their nodes ONCE per group, then
 * applies every sibling action to that original set. Re-selecting after an
 * earlier sibling mutated the DOM would miss nodes (removing class `locked`
 * makes selector `.locked` stop matching), so siblinghood is never guessed
 * from adjacent selectors.
 *
 * Nested [CleanupOp.children] run globally (document scope) exactly once when
 * the parent group matched at least one node — mirroring the extension's
 * `if (first && elem.elems)` — and are skipped when the parent matched
 * nothing. A removal still counts as a parent match: the condition is the
 * original pre-mutation snapshot, not the survivors.
 */
object SiteCleanup {
    /** One selector the engine failed to run: operation, selector, and real error. */
    data class CleanupFailure(
        val groupId: Int,
        val selector: String,
        val errorClass: String,
        val message: String?,
    )

    /** What one [apply] run did, for notices and tests. */
    data class Report(
        /** Groups whose selector matched at least one node. */
        val groupsMatched: Int,
        /** Nodes removed by HIDE/REMOVE across all groups (children included). */
        val nodesRemoved: Int,
        /** Attribute/class names stripped across all groups (children included). */
        val namesStripped: Int,
        /** Selectors that threw instead of matching (children included). Never silent. */
        val errors: List<CleanupFailure> = emptyList(),
    ) {
        /** True when at least one group matched (directives were applied). */
        fun applied(): Boolean = groupsMatched > 0

        /** True when at least one selector failed; callers must fail the read. */
        fun hasErrors(): Boolean = errors.isNotEmpty()
    }

    /**
     * Applies [ops] to [doc] in place and reports what matched. Selectors and
     * names were validated at import, but a selector Jsoup cannot run against
     * this document is recorded in [Report.errors] — with its group,
     * selector, and real error — and remaining groups still run so one bad
     * selector never hides the rest. A failed group counts as neither
     * matched nor a parent for nested ops. Callers must fail the read when
     * [Report.hasErrors] is true: the uncleaned page may still show blocked
     * UI that must not be spoken as an article.
     */
    fun apply(doc: Document, ops: List<CleanupOp>): Report {
        var groupsMatched = 0
        var nodesRemoved = 0
        var namesStripped = 0
        val errors = mutableListOf<CleanupFailure>()
        for (group in ops.groupById()) {
            val first = group.first()
            val snapshot = try {
                doc.select(first.selector)
            } catch (e: Exception) {
                errors.add(CleanupFailure(first.groupId, first.selector, e.javaClass.name, e.message))
                continue
            }
            if (snapshot.isEmpty()) continue
            groupsMatched++
            val nodes = snapshot.toList()
            for (op in group) {
                when (op.kind) {
                    CleanupKind.HIDE, CleanupKind.REMOVE -> {
                        for (node in nodes) {
                            node.remove()
                            nodesRemoved++
                        }
                    }
                    CleanupKind.REMOVE_ATTRS -> {
                        for (node in nodes) {
                            for (name in op.names) {
                                if (node.hasAttr(name)) {
                                    node.removeAttr(name)
                                    namesStripped++
                                }
                            }
                        }
                    }
                    CleanupKind.REMOVE_CLASSES -> {
                        for (node in nodes) {
                            for (name in op.names) {
                                if (node.hasClass(name)) {
                                    node.removeClass(name)
                                    namesStripped++
                                }
                            }
                        }
                    }
                }
            }
            // One shared nested run per matched parent group: every sibling's
            // children (the importer hangs them on the first sibling only)
            // execute once against the whole document.
            val nested = group.flatMap { it.children }
            if (nested.isNotEmpty()) {
                val child = apply(doc, nested)
                groupsMatched += child.groupsMatched
                nodesRemoved += child.nodesRemoved
                namesStripped += child.namesStripped
                errors.addAll(child.errors)
            }
        }
        return Report(groupsMatched, nodesRemoved, namesStripped, errors)
    }

    /**
     * Groups ops by [CleanupOp.groupId] in first-seen order, preserving
     * source order within each group (extension order: remove, classes,
     * attributes).
     */
    private fun List<CleanupOp>.groupById(): List<List<CleanupOp>> {
        val order = LinkedHashMap<Int, MutableList<CleanupOp>>()
        for (op in this) {
            order.getOrPut(op.groupId) { mutableListOf() }.add(op)
        }
        return order.values.toList()
    }
}
