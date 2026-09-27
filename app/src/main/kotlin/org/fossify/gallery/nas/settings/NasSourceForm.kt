package org.fossify.gallery.nas.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fossify.gallery.R
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import java.util.UUID

/** Dialog-only form: credentials never enter saved view state or an Intent/Bundle. */
class NasSourceForm(context: Context, private val existing: SavedNasSource?, credentials: NasCredentials?) {
    private val fields = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        isSaveFromParentEnabled = false
    }
    val view = ScrollView(context).apply { addView(fields) }
    private val displayName = field(R.string.nas_display_name, existing?.displayName.orEmpty())
    private val host = field(R.string.nas_host, existing?.source?.host?.value.orEmpty())
    private val share = field(R.string.nas_share, existing?.source?.share.orEmpty())
    private val root = field(R.string.nas_root, existing?.source?.root?.value.orEmpty())
    private val username = field(R.string.nas_username, credentials?.username.orEmpty())
    private val password = field(R.string.nas_password, credentials?.password.orEmpty(), secret = true)
    private val vpn = CheckBox(context).apply {
        text = context.getString(R.string.nas_vpn)
        isChecked = existing?.source?.mode == NasConnectionMode.VPN
        fields.addView(this)
    }
    val needsCredentials = existing != null && credentials == null

    init {
        fields.addView(TextView(context).apply {
            setText(if (needsCredentials) R.string.nas_credentials_unavailable else R.string.nas_settings_only)
        })
    }

    fun draft(): SavedNasSource {
        val source = NasSource(
            existing?.source?.key ?: NasSourceKey(UUID.randomUUID(), 1),
            NasHost.parse(host.text.toString().trim()),
            share.text.toString(),
            NasRelativePath.parse(root.text.toString()),
            if (vpn.isChecked) NasConnectionMode.VPN else NasConnectionMode.LAN,
            existing?.source?.credentialRef ?: UUID.randomUUID()
        )
        return SavedNasSource(source, displayName.text.toString())
    }

    fun credentials() = NasCredentials(username.text.toString(), password.text.toString())

    fun clearCredentials() {
        username.text.clear()
        password.text.clear()
    }

    private fun field(label: Int, value: String, secret: Boolean = false): EditText {
        val edit = EditText(fields.context).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or if (secret) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            filters = arrayOf(InputFilter.LengthFilter(MAX_FIELD_LENGTH))
            isSaveEnabled = false
            isSaveFromParentEnabled = false
            id = View.generateViewId()
            setText(value)
        }
        fields.addView(TextView(fields.context).apply {
            setText(label)
            labelFor = edit.id
        })
        fields.addView(edit)
        return edit
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 4096
        const val PADDING_DP = 16
    }
}
