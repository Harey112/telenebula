package com.telenebula.app.ui.screens.dex

import androidx.lifecycle.ViewModel
import com.telenebula.app.nav.Navigator

class DexViewModel(private val navigator: Navigator) : ViewModel() {
    fun goBack() = navigator.pop()
}
