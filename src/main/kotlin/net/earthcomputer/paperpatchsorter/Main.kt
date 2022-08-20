@file:JvmName("Main")

package net.earthcomputer.paperpatchsorter

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dialog
import java.awt.Dimension
import java.awt.event.ItemEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import javax.swing.AbstractListModel
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.ListDataEvent
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.useDirectoryEntries
import kotlin.io.path.useLines
import kotlin.io.path.writeLines

val PATCHES_DIR = Path("paper", "patches", "server")
val PATCH_NAME_REGEX = """(\d+)-.+\.patch""".toRegex()
val CATEGORIES_FILE = Path("paper-categories.csv")

lateinit var mainFrame: JFrame

val paperPatches = mutableListOf<String>()
var filteredPatches = emptyList<String>()
val patchCategories = mutableMapOf<String, EnumSet<Category>>()

enum class Category(val shortName: String, val shortcutKey: Int) {
    API("api", KeyEvent.VK_A),
    PERFORMANCE("perf", KeyEvent.VK_P),
    BUG_FIX("fix", KeyEvent.VK_F),
    SECURITY("sec", KeyEvent.VK_S),
    FIX_SPIGOT_BULLSHIT("spigot", KeyEvent.VK_T),
    OTHER("other", KeyEvent.VK_O),
    ;
}

fun main() {
    // set look and feel
    UIManager.getInstalledLookAndFeels()
        .firstOrNull { it.name == "Nimbus" }
        ?.let { UIManager.setLookAndFeel(it.className) }

    // create main frame
    mainFrame = JFrame("Paper Patch Sorter")
    val contentPane = JPanel()
    buildPane(contentPane)
    mainFrame.contentPane.add(contentPane)
    mainFrame.pack()
    mainFrame.setLocationRelativeTo(null)
    mainFrame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    mainFrame.isVisible = true

    // clone paper if not exists
    if (!PATCHES_DIR.exists()) {
        File("paper").deleteRecursively()
        val gitFuture = ProcessBuilder(
            "git",
            "clone",
            "--depth=1",
            "--single-branch",
            "https://github.com/PaperMC/Paper",
            "paper",
        ).inheritIO().start().onExit()

        try {
            showProgressBarDialog(gitFuture, "Cloning Paper repository")
        } catch (e: Throwable) {
            System.err.println("Failed to clone paper repository")
            e.printStackTrace()
            return
        }
    }

    // load all patches
    PATCHES_DIR.useDirectoryEntries("*.patch") { entries ->
        paperPatches += entries
            .mapNotNull { PATCH_NAME_REGEX.matchEntire(it.fileName.toString()) }
            .mapNotNull { match -> match.groupValues[1].toIntOrNull()?.let { it to match.groupValues[0] } }
            .sortedBy { (index, _) -> index }
            .map { (_, name) -> name }
    }
    filteredPatches = paperPatches

    // load categories
    try {
        CATEGORIES_FILE.useLines {  lines ->
            for (line in lines.drop(1)) {
                val parts = line.split(",")
                patchCategories[parts[0].substringAfter('-')] = parts.drop(1)
                    .mapNotNull { part -> Category.values().firstOrNull { it.shortName == part } }
                    .toCollection(EnumSet.noneOf(Category::class.java))
            }
        }
    } catch (e: IOException) {
        System.err.println("Failed to load categories file: $e")
    }

    PaperPatchesModel.refresh()
}

object PaperPatchesModel : AbstractListModel<String>() {
    override fun getSize() = filteredPatches.size

    override fun getElementAt(index: Int) = buildString {
        append(filteredPatches[index])

        val categories = patchCategories[filteredPatches[index].substringAfter('-')]
        if (!categories.isNullOrEmpty()) {
            append(' ')
            append(categories.joinToString(",") { it.shortName })
        }
    }

    fun refresh() {
        for (listener in listDataListeners) {
            listener.contentsChanged(ListDataEvent(this, ListDataEvent.CONTENTS_CHANGED, 0, filteredPatches.size))
        }
    }
}

private fun buildPane(contentPane: JPanel) {
    contentPane.preferredSize = Dimension(640, 480)
    contentPane.layout = BorderLayout()
    val jList = JList(PaperPatchesModel)
    contentPane.add(JScrollPane(jList), BorderLayout.CENTER)
    jList.cellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            component.background = if (index % 2 == 0) Color.WHITE else Color.LIGHT_GRAY
            return component
        }
    }
    jList.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) {
                val listIndex = jList.locationToIndex(e.point)
                if (listIndex >= 0) {
                    try {
                        Desktop.getDesktop().open(PATCHES_DIR.resolve(filteredPatches[listIndex]).toFile())
                    } catch (e: Throwable) {
                        System.err.println("Failed to open file: $e")
                    }
                }
            }
        }
    })
    jList.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            val category = Category.values().firstOrNull { it.shortcutKey == e.keyCode }
            if (category != null && jList.selectedIndex >= 0) {
                val set = patchCategories.computeIfAbsent(
                    filteredPatches[jList.selectedIndex].substringAfter('-')
                ) { EnumSet.noneOf(Category::class.java) }
                if (category in set) {
                    set -= category
                } else {
                    set += category
                }
                PaperPatchesModel.refresh()
                save()
            }
        }
    })

    val instructions = JPanel()
    instructions.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
    instructions.layout = BoxLayout(instructions, BoxLayout.PAGE_AXIS)
    for (category in Category.values()) {
        instructions.add(JLabel("${category.name.lowercase()}: ${KeyEvent.getKeyText(category.shortcutKey)}"))
    }
    contentPane.add(instructions, BorderLayout.SOUTH)

    val filterPanel = JPanel()
    filterPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
    filterPanel.add(JLabel("Filter by: "))
    val filterComboBox = JComboBox(arrayOf(Filter.NoFilter, Filter.UncategorizedFilter) +
            Category.values().map { Filter.CategoryFilter(it) }.toTypedArray())
    filterComboBox.addItemListener { event ->
        if (event.stateChange == ItemEvent.SELECTED) {
            val filter = event.item as Filter? ?: Filter.NoFilter
            filteredPatches = paperPatches.filter { filter.test(it) }
            PaperPatchesModel.refresh()
        }
    }
    filterPanel.add(filterComboBox)
    contentPane.add(filterPanel, BorderLayout.NORTH)
}

sealed interface Filter {
    fun test(patch: String): Boolean

    object NoFilter : Filter {
        override fun test(patch: String) = true

        override fun toString() = "none"
    }

    object UncategorizedFilter : Filter {
        override fun test(patch: String): Boolean {
            return patchCategories[patch.substringAfter('-')].isNullOrEmpty()
        }

        override fun toString() = "uncategorized"
    }

    class CategoryFilter(private val ctgy: Category) : Filter {
        override fun test(patch: String): Boolean {
            val categories = patchCategories[patch.substringAfter('-')]
            return categories != null && ctgy in categories
        }

        override fun toString() = ctgy.name.lowercase()
    }
}

private fun save() {
    val tempFile = Path("categories.csv.swp")
    tempFile.writeLines(sequenceOf("patch,categories...") +
        paperPatches.asSequence().mapNotNull { patch ->
            val categories = patchCategories[patch.substringAfter('-')]
            if (categories.isNullOrEmpty()) {
                null
            } else {
                "$patch,${categories.joinToString(",") { it.shortName }}"
            }
        }
    )
    tempFile.moveTo(CATEGORIES_FILE, overwrite = true)
}

/**
 * Shows a progress bar dialog that waits until the given future is complete.
 * Also blocks until the future is complete.
 */
private fun showProgressBarDialog(future: CompletableFuture<*>, message: String) {
    if (SwingUtilities.isEventDispatchThread()) {
        throw IllegalStateException("Cannot wait for future on EDT")
    }

    val dialog = JDialog(mainFrame, "Paper Patch Sorter", Dialog.ModalityType.APPLICATION_MODAL)
    SwingUtilities.invokeLater {
        val contentPane = JPanel()
        contentPane.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        contentPane.layout = BorderLayout()
        contentPane.add(JLabel(message), BorderLayout.NORTH)
        contentPane.add(JProgressBar().also { progressBar ->
            progressBar.isIndeterminate = true
        })
        dialog.contentPane.add(contentPane)
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    future.join()

    SwingUtilities.invokeLater {
        dialog.isVisible = false
        dialog.dispose()
    }
}
