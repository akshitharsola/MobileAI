// Based on MLCChat from MLC-LLM (https://github.com/mlc-ai/mlc-llm), Apache 2.0 License
// Extended by Akshit Harsola — adds Home, Models, Settings screens
package ai.mlc.mobileai

import android.app.Activity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@ExperimentalMaterial3Api
@Composable
fun NavView(activity: Activity, appViewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { SplashScreen(navController) }
        composable("home") { HomeScreen(navController, appViewModel, activity as MainActivity) }
        composable("models") { StartView(navController, appViewModel) }
        composable("chat") { ChatView(navController, appViewModel.chatState, activity) }
        composable("settings") { SettingsScreen(navController, activity as MainActivity, appViewModel) }
    }
}
