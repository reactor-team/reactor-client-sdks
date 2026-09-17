package inc.reactor.examples.android.scenario06
import android.content.Context
import inc.reactor.examples.android.Scenarios
fun run(context: Context, scope: kotlinx.coroutines.CoroutineScope, model: String, token: String) = Scenarios(context, scope).runScenario(6, model, token)
