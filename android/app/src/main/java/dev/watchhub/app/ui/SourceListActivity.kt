package dev.watchhub.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.watchhub.app.R
import dev.watchhub.app.databinding.ActivitySourceListBinding

/**
 * 源管理（独立二级页）。
 *
 * 2.2.1 把底栏的「源」tab 合并进「订阅」：日常只阅读，需要改抓取规则时从订阅页
 * 顶部的「源管理」进来。源页本体（SourcesFragment）原样复用，避免两套实现。
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
