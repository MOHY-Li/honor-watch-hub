package dev.watchhub.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ActivitySourceListBinding

/**
 * 源管理（独立二级页）：只管高级监控源（JSON/正则/指纹）。
 *
 * 2.2.1 把底栏的「源」tab 合并进「订阅」；2.4.2 进一步把 RSS 的增删改全部收敛到订阅页，
 * 这里只剩抓取规则类源，避免同一个 RSS 有两个管理入口。源页本体（SourcesFragment）原样复用。
 */
class SourceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SourcesFragment())
                .commit()
        }
    }
}
