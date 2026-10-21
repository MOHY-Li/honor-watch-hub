package dev.watchhub.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.watchhub.app.databinding.ActivitySourcesBinding
import dev.watchhub.app.hub.SourceRepo

/** 直接编辑源的 JSON 配置。保存前做一次解析校验，避免存进去跑不起来。 */
class SourcesActivity : AppCompatActivity() {

    private lateinit var b: ActivitySourcesBinding
    private lateinit var repo: SourceRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySourcesBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = SourceRepo(this)
        b.etJson.setText(repo.rawJson())

        b.btnCancel.setOnClickListener { finish() }
        b.btnSave.setOnClickListener {
            val text = b.etJson.text?.toString().orEmpty()
            repo.saveRaw(text).fold(
                onSuccess = { list ->
                    Toast.makeText(this, "已保存 ${list.size} 个源", Toast.LENGTH_SHORT).show()
                    finish()
                },
                onFailure = { e ->
                    Toast.makeText(this, "JSON 有误：${e.message}", Toast.LENGTH_LONG).show()
                },
            )
        }
    }
}
