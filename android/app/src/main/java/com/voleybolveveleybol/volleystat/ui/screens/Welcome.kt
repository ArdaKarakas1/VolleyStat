package com.voleybolveveleybol.volleystat.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voleybolveveleybol.volleystat.R
import com.voleybolveveleybol.volleystat.ui.theme.LocalVsColors

/** Shown once. It never blocks: one tap leads to the home screen. */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val colors = LocalVsColors.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.heroStart, colors.heroEnd)))) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_sports_volleyball_filled), contentDescription = null, tint = colors.onHero, modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, color = colors.onHero, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, color = colors.onHero, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.brand_logo), contentDescription = stringResource(R.string.logo_description), modifier = Modifier.size(36.dp).clip(CircleShape))
                Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleSmall, color = colors.onHero)
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.welcome_today), style = MaterialTheme.typography.bodyMedium, color = colors.onHero, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = colors.onHero, contentColor = colors.heroStart),
            ) {
                Text(stringResource(R.string.action_start), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}
