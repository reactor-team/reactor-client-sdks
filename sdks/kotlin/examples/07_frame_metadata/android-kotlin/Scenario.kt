package inc.reactor.examples.android.scenario07
import android.content.Context
import inc.reactor.examples.android.Scenarios
fun run(context: Context, scope: kotlinx.coroutines.CoroutineScope, model: String, token: String) = Scenarios(context, scope).runScenario(7, model, token)
