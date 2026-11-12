package io.github.kaito65452.blazelauncher.rustruntime.ui

import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.ui.AndroidStringText
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.screens.TitledNavKey
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data object RustEditionNavKey : TitledNavKey {
    @Contextual
    override val title: AndroidStringText = androidText(R.string.rust_edition)
}
