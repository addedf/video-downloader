package com.zemin.downloader.ui

import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding>(
    private val inflateBinding: (LayoutInflater) -> VB
) : AppCompatActivity() {

    protected lateinit var binding: VB
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflateBinding(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
    }

    protected fun applySystemBarInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
