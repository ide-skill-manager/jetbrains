package dev.agentry.jetbrains.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import dev.agentry.jetbrains.registry.BranchListService
import dev.agentry.jetbrains.registry.RemoteRefs
import dev.agentry.jetbrains.util.InputValidation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Add Registry dialog.
 *
 * Replaces the prior `Messages.showInputDialog` pair (URL, then ref) with a single form
 * where the URL field validates via `git ls-remote` and the ref is picked from a real
 * dropdown populated by the remote's actual branches and tags. If `ls-remote` fails
 * (offline, auth, host) the ref combo gracefully falls back to a free-text mode so the
 * dialog still works.
 */
class AddRegistryDialog(
    project: Project,
    private val branchService: BranchListService = BranchListService.getInstance()
) : DialogWrapper(project, true) {

    /** Sentinel inserted into the ref combo to trigger commit-SHA entry. */
    private val sentinelCustomSha = "— Use a commit SHA… —"

    private val urlField = JBTextField(40)
    private val urlStatus = JBLabel(" ").apply {
        foreground = JBColor.GRAY
    }
    private val validateButton = JButton("Validate")
    private val refCombo = JComboBox<String>().apply {
        isEditable = false
        isEnabled = false
    }
    private val refTextField = JBTextField(20).apply { isVisible = false }
    private val refSpinner = JBLabel("").apply { isVisible = false }
    private val nameField = JBTextField(40)
    private val enableCheck = JCheckBox("Enable on add", true)

    /** Filled in after the user clicks OK. */
    var result: Result? = null
        private set

    private var lastValidatedUrl: String? = null
    private var validating: Boolean = false

    init {
        title = "Add Registry"
        setOKButtonText("Add")
        init()
        wireFocusValidation()
        wireRefComboSelection()
        validateButton.addActionListener { runValidation(urlField.text.trim()) }
        urlField.document.addDocumentListener(SimpleDocumentListener {
            // Editing the URL invalidates the previous validation result.
            if (urlField.text.trim() != lastValidatedUrl) {
                resetRefState()
                setStatus("", JBColor.GRAY)
            }
            updateOkEnabled()
        })
        updateOkEnabled()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }

        // URL row.
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("URL:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val urlRow = JPanel(BorderLayout(4, 0))
        urlRow.add(urlField, BorderLayout.CENTER)
        urlRow.add(validateButton, BorderLayout.EAST)
        panel.add(urlRow, gbc)

        gbc.gridx = 1; gbc.gridy = 1
        panel.add(urlStatus, gbc)

        // Ref row.
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JBLabel("Ref:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val refRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        refRow.add(refCombo)
        refRow.add(refTextField)
        refRow.add(refSpinner)
        panel.add(refRow, gbc)

        // Name row.
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        panel.add(JBLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(nameField, gbc)

        gbc.gridx = 1; gbc.gridy = 4
        panel.add(
            JBLabel("(optional, shown in the tool window)").apply { foreground = JBColor.GRAY },
            gbc
        )

        // Enable checkbox.
        gbc.gridx = 1; gbc.gridy = 5
        panel.add(enableCheck, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val url = urlField.text.trim()
        if (url.isBlank()) return ValidationInfo("URL is required.", urlField)
        if (!InputValidation.isValidRegistryUrl(url)) {
            return ValidationInfo(
                "URL must use https, http, ssh, or git protocol (or scp-form user@host:path).",
                urlField
            )
        }
        val ref = selectedRef() ?: return ValidationInfo("Pick a ref.", refCombo)
        if (ref != "HEAD" && !InputValidation.isValidGitRef(ref)) {
            val target: JComponent = if (refTextField.isVisible) refTextField else refCombo
            return ValidationInfo(
                "Ref must be a valid branch/tag/commit (no spaces, no leading '-').",
                target
            )
        }
        return null
    }

    override fun doOKAction() {
        val url = urlField.text.trim()
        val ref = selectedRef() ?: return
        result = Result(
            url = url,
            ref = ref,
            name = nameField.text.trim(),
            enabled = enableCheck.isSelected
        )
        super.doOKAction()
    }

    // --- URL validation ---------------------------------------------------------------

    private fun wireFocusValidation() {
        urlField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                val url = urlField.text.trim()
                if (url.isNotBlank()
                    && url != lastValidatedUrl
                    && InputValidation.isValidRegistryUrl(url)
                ) {
                    runValidation(url)
                }
            }
        })
    }

    private fun runValidation(url: String) {
        if (validating) return
        if (url.isBlank() || !InputValidation.isValidRegistryUrl(url)) {
            setStatus(
                "✗ URL must use https, http, ssh, or git protocol.",
                JBColor.RED
            )
            resetRefState()
            updateOkEnabled()
            return
        }
        validating = true
        setStatus("Checking…", JBColor.GRAY)
        refSpinner.text = "loading refs…"
        refSpinner.isVisible = true
        refCombo.isEnabled = false
        refTextField.isVisible = false
        updateOkEnabled()

        // Run off-EDT; mutate UI inside invokeLater.
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, "Agentry: validating registry URL", true) {
                override fun run(indicator: ProgressIndicator) {
                    val result = branchService.fetch(url)
                    ApplicationManager.getApplication().invokeLater {
                        validating = false
                        refSpinner.isVisible = false
                        if (result.isSuccess) {
                            applyRefs(url, result.getOrThrow())
                        } else {
                            applyValidationFailure(result.exceptionOrNull())
                        }
                        updateOkEnabled()
                    }
                }
            }
        )
    }

    private fun applyRefs(url: String, refs: RemoteRefs) {
        lastValidatedUrl = url
        if (refs.isEmpty) {
            setStatus(
                "✓ Reachable, but no branches or tags found. You can still enter a ref manually.",
                successColor()
            )
            switchToTextRef("HEAD")
            return
        }
        setStatus(
            "✓ Reachable. Found ${refs.branches.size} branch(es), ${refs.tags.size} tag(s).",
            successColor()
        )
        val items = buildComboItems(refs)
        refCombo.model = DefaultComboBoxModel(items.toTypedArray())
        val preferred = refs.defaultRef?.takeIf { it in refs.branches } ?: items.firstOrNull()
        refCombo.selectedItem = preferred
        refCombo.isEnabled = true
        refTextField.isVisible = false
    }

    private fun applyValidationFailure(error: Throwable?) {
        val message = error?.message.orEmpty()
        val display = classifyError(message)
        setStatus(display, JBColor.RED)
        // Fall back to text-entry so the dialog isn't gated on a working network.
        switchToTextRef(refTextField.text.ifBlank { "HEAD" })
    }

    private fun classifyError(raw: String): String = when {
        raw.contains("Authentication failed", ignoreCase = true)
            || raw.contains("could not read Username") -> "✗ Authentication required — check your git credential helper for this host."
        raw.contains("could not resolve host", ignoreCase = true)
            || raw.contains("connection refused", ignoreCase = true) -> "✗ Host unreachable."
        raw.contains("not a git repository", ignoreCase = true) -> "✗ Not a git repository."
        raw.contains("timed out", ignoreCase = true) -> "✗ Timed out waiting for the remote."
        raw.contains("Invalid registry URL", ignoreCase = true) -> "✗ URL must use https, http, ssh, or git protocol."
        else -> "✗ Unable to reach the remote. Enter a ref manually below."
    }

    // --- Ref combo / text fallback ----------------------------------------------------

    private fun buildComboItems(refs: RemoteRefs): List<String> {
        val items = mutableListOf<String>()
        // Default ref first (if it's a branch we know about).
        val defaultRef = refs.defaultRef
        if (defaultRef != null && defaultRef in refs.branches) items += defaultRef
        // Then the other branches.
        items += refs.branches.filter { it != defaultRef }
        if (refs.tags.isNotEmpty()) {
            items += "— tags —"
            items += refs.tags
        }
        items += sentinelCustomSha
        return items
    }

    private fun wireRefComboSelection() {
        refCombo.addActionListener {
            val selected = refCombo.selectedItem as? String ?: return@addActionListener
            when {
                selected == sentinelCustomSha -> switchToTextRef("")
                selected.startsWith("—") -> {
                    // Section separator — reselect the previous valid item.
                    val model = refCombo.model
                    for (i in 0 until model.size) {
                        val item = model.getElementAt(i)
                        if (!item.startsWith("—") && item != sentinelCustomSha) {
                            refCombo.selectedItem = item
                            break
                        }
                    }
                }
            }
            updateOkEnabled()
        }
    }

    private fun switchToTextRef(initial: String) {
        refCombo.isEnabled = false
        refTextField.text = initial
        refTextField.isVisible = true
        refTextField.requestFocusInWindow()
    }

    private fun resetRefState() {
        refCombo.model = DefaultComboBoxModel(arrayOf<String>())
        refCombo.isEnabled = false
        refTextField.isVisible = false
    }

    /** What the user has currently picked (or null if no valid choice yet). */
    private fun selectedRef(): String? = when {
        refTextField.isVisible -> refTextField.text.trim().ifBlank { null }
        else -> (refCombo.selectedItem as? String)
            ?.takeIf { it.isNotBlank() && !it.startsWith("—") && it != sentinelCustomSha }
    }

    // --- Status line styling ----------------------------------------------------------

    private fun setStatus(text: String, color: Color) {
        urlStatus.text = text.ifBlank { " " }
        urlStatus.foreground = color
    }

    private fun successColor(): Color =
        JBColor(Color(0, 128, 0), Color(120, 200, 120))

    // --- OK enable wiring -------------------------------------------------------------

    private fun updateOkEnabled() {
        val urlOk = InputValidation.isValidRegistryUrl(urlField.text.trim())
        val refOk = selectedRef() != null
        isOKActionEnabled = urlOk && refOk && !validating
    }

    data class Result(
        val url: String,
        val ref: String,
        val name: String,
        val enabled: Boolean
    )
}

/** Minimal DocumentListener that calls one lambda on any change. */
private class SimpleDocumentListener(private val onChange: () -> Unit) : javax.swing.event.DocumentListener {
    override fun insertUpdate(e: javax.swing.event.DocumentEvent) = onChange()
    override fun removeUpdate(e: javax.swing.event.DocumentEvent) = onChange()
    override fun changedUpdate(e: javax.swing.event.DocumentEvent) = onChange()
}
