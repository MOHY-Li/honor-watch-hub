package dev.watchhub.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ActivitySourceEditBinding
import dev.watchhub.app.source.SourceKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据源编辑。
 *
 * - 单源模式：把编辑后的 JSON 对象合并回 `HubUi.rawSourcesJson()` 的数组再整体保存；
 * - 新增模式：用 [HubUi.sourceTemplate] 填初始 JSON，保存时检查名字不重复；
 * - 全部模式：直接编辑整个 JSON 数组。
 */
class SourceEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "source_name"
        const val EXTRA_KIND = "source_kind"
        const val EXTRA_EDIT_ALL = "edit_all"
    }

    private lateinit var binding: ActivitySourceEditBinding
    private var originalName: String? = null
    private var editAll = false
    private var isNew = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        editAll = intent.getBooleanExtra(EXTRA_EDIT_ALL, false)
        originalName = intent.getStringExtra(EXTRA_NAME)
        val kindRaw = intent.getStringExtra(EXTRA_KIND)

        when {
            editAll -> {
                binding.toolbar.title = getString(R.string.sources_edit_all)
                binding.hint.text = "高级源（JSON/正则/指纹）数组。RSS 订阅不在此编辑，保存时原样保留。"
                binding.etJson.setText(runCatching { HubUi.rawAdvancedSourcesJson(this) }.getOrDefault("[]"))
                binding.btnDelete.visibility = android.view.View.GONE
            }
            originalName != null -> {
                val name = originalName!!
                val json = runCatching { HubUi.sourceJsonForEdit(this, name) }.getOrNull()
                if (json == null) {
                    Toast.makeText(this, "数据源不存在或已删除", Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                binding.toolbar.title = getString(R.string.source_edit_edit) + " · " + name
                binding.etJson.setText(json)
                binding.btnDelete.visibility = android.view.View.VISIBLE
            }
            else -> {
                isNew = true
                val kind = SourceKind.parse(kindRaw) ?: SourceKind.RSS
                binding.toolbar.title = getString(R.string.source_edit_new)
                binding.hint.text = "新数据源 JSON（name 不能与现有源重名）"
                binding.etJson.setText(runCatching { HubUi.sourceTemplate(kind) }.getOrDefault("{}"))
                binding.btnDelete.visibility = android.view.View.GONE
            }
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun save() {
        val raw = binding.etJson.text?.toString().orEmpty()
        if (editAll) {
            saveAll(raw)
            return
        }
        val obj = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            errorDialog(getString(R.string.source_edit_invalid_json, t.message ?: "解析失败"))
            return
        }
        val name = obj.optString("name").trim()
        if (name.isBlank()) {
            errorDialog(getString(R.string.source_edit_name_missing))
            return
        }
        val existing = runCatching { HubUi.sourceRows(this).map { it.name } }.getOrDefault(emptyList())
        if (isNew && existing.any { it == name }) {
            errorDialog(getString(R.string.source_edit_name_duplicate, name))
            return
        }
        if (!isNew && originalName != null && name != originalName && existing.any { it == name }) {
            errorDialog(getString(R.string.source_edit_name_duplicate, name))
            return
        }

        val array = try {
            JSONArray(runCatching { HubUi.rawSourcesJson(this) }.getOrDefault("[]"))
        } catch (_: Throwable) {
            errorDialog("现有数据源配置不是合法数组，请用「编辑全部 JSON」修复")
            return
        }

        var replaced = false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (originalName != null && item.optString("name") == originalName) {
                array.put(i, obj)
                replaced = true
                break
            }
        }
        if (!replaced) array.put(obj)

        saveJson(array.toString(2))
    }

    private fun saveAll(raw: String) {
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            errorDialog(getString(R.string.source_edit_invalid_json, t.message ?: "解析失败"))
            return
        }
        val names = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: run {
                    errorDialog("第 ${i + 1} 项不是 JSON 对象")
                    return
                }
            val name = item.optString("name").trim()
            if (name.isBlank()) {
                errorDialog("第 ${i + 1} 项缺少 name")
                return
            }
            if (!names.add(name)) {
                errorDialog(getString(R.string.source_edit_name_duplicate, name))
                return
            }
        }
        saveJson(array.toString(2))
    }

    private fun saveJson(json: String) {
        val result = if (editAll) HubUi.saveAdvancedSourcesJson(this, json) else HubUi.saveSourcesJson(this, json)
        result
            .onSuccess {
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
            .onFailure { error ->
                errorDialog(error.message ?: "保存失败")
            }
    }

    private fun confirmDelete() {
        val name = originalName ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.source_delete_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { HubUi.deleteSource(this, name) }
                    .onSuccess { finish() }
                    .onFailure {
                        Toast.makeText(this, "删除失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .show()
    }

    private fun errorDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("保存失败")
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
