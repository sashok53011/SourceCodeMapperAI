package online.devhorizon.sourcecodemapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import online.devhorizon.sourcecodemapper.ui.App
import online.devhorizon.sourcecodemapper.ui.ScmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScmTheme {
                val vm: MainViewModel = viewModel()
                App(vm)
            }
        }
    }
}
