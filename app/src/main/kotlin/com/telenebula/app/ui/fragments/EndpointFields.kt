package com.telenebula.app.ui.fragments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnSpace

@Composable
fun EndpointFields(
    host: String,
    port: String,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    modifier: Modifier = Modifier,
    hostLabel: String = "Public IP",
    hostPlaceholder: String = "47.104.245.138",
    portPlaceholder: String = "4242",
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TnSpace.sm), verticalAlignment = Alignment.Top) {
        TnTextField(host, onHost, modifier = Modifier.weight(1f), label = hostLabel, placeholder = hostPlaceholder, isMono = true, hasAutoCapitalize = false)
        TnTextField(port, onPort, modifier = Modifier.width(PORT_WIDTH), label = "Port", placeholder = portPlaceholder, isMono = true, isNumeric = true, hasAutoCapitalize = false)
    }
}

private val PORT_WIDTH = 108.dp
