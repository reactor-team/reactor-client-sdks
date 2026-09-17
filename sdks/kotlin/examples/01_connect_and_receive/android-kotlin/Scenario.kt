package inc.reactor.examples.android.scenario01
import android.content.Context
import inc.reactor.examples.android.Scenarios
import inc.reactor.sdk.Reactor
fun run(context: Context, scope: kotlinx.coroutines.CoroutineScope, model: String, token: String) = Scenarios(context, scope).runScenario(1, model, token)
