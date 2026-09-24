package inc.reactor.sdk.android.integration

import androidx.test.runner.AndroidJUnitRunner

/**
 * The runner, so the shared session is torn down exactly once when the run ends.
 *
 * JUnit 4 has `@AfterClass` and nothing wider, and this suite shares one session across every
 * class — see [LiveSession] for why. Without a hook at the end of the *run*, the last class to
 * finish would leave that session open, and a creator that goes away without disconnecting
 * orphans it server-side until its lease clears. The next run of this suite is what pays for
 * that, which is a confusing failure to inherit.
 */
public class LiveRunner : AndroidJUnitRunner() {
    override fun onDestroy() {
        LiveSession.closeIfOpen()
        super.onDestroy()
    }
}
