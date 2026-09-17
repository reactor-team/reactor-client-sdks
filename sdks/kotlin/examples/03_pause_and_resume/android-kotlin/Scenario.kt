package inc.reactor.examples.android.scenario03
import android.content.Context
import inc.reactor.examples.android.Scenarios
fun run(context: Context, scope: kotlinx.coroutines.CoroutineScope, model: String, token: String) = Scenarios(context, scope).runScenario(3, model, token)
