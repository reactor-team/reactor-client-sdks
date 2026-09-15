package inc.reactor.examples.desktop;

import inc.reactor.sdk.jvm.JavaReactor;

/** The same seven scenarios for a Java desktop consumer. */
public final class Scenarios {
  private Scenarios() {}

  public static void runScenario(int number, String model, String token) throws Exception {
    if (number < 1 || number > 7) throw new IllegalArgumentException("scenario must be 1..7");
    try (JavaReactor client = new JavaReactor(model, token)) {
      client.connect();
      client.runScenario(number);
    }
  }
}
