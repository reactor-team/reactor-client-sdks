package inc.reactor.examples.desktop;
import inc.reactor.examples.ExampleConfig;
public final class Main { public static void main(String[] a) throws Exception { var c=ExampleConfig.fromEnvironment(); Scenarios.runScenario(2,c.getModel(),c.getToken()); } }
