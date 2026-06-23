package ai.mlc.mobileai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import ai.localis.app.R

private val SplashGradientTop = Color(0xFFE5C541)
private val SplashGradientBottom = Color(0xFFD6573F)

@ExperimentalMaterial3Api
@Composable
fun SplashScreen(navController: NavController) {
    var logoVisible by remember { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "splashLogoAlpha"
    )

    LaunchedEffect(Unit) {
        logoVisible = true
        delay(2200)
        navController.navigate("home") {
            popUpTo("splash") { inclusive = true }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(SplashGradientTop, SplashGradientBottom),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = null,
            modifier = Modifier.size(200.dp).alpha(logoAlpha)
        )
    }
}
