/** A minimal typed pub/sub — `on`/`off`/`once` over a fixed event map.
 *
 * `EventMap` is deliberately unconstrained: a `Record<string, ...>` bound
 * would force every event map to declare a string index signature, which
 * would let any string through as an event name and throw away the whole
 * point of typing this per call site. */
export class Emitter<EventMap extends { [Name in keyof EventMap]: (...args: never[]) => void }> {
  private readonly listeners = new Map<keyof EventMap, Set<EventMap[keyof EventMap]>>();

  on<Name extends keyof EventMap>(event: Name, handler: EventMap[Name]): void {
    let handlers = this.listeners.get(event);

    if (!handlers) {
      handlers = new Set();
      this.listeners.set(event, handlers);
    }
    handlers.add(handler);
  }

  off<Name extends keyof EventMap>(event: Name, handler: EventMap[Name]): void {
    this.listeners.get(event)?.delete(handler);
  }

  once<Name extends keyof EventMap>(event: Name, handler: EventMap[Name]): void {
    const wrapped = ((...args: Parameters<EventMap[Name]>) => {
      this.off(event, wrapped);
      (handler as (...args: Parameters<EventMap[Name]>) => void)(...args);
    }) as EventMap[Name];

    this.on(event, wrapped);
  }

  emit<Name extends keyof EventMap>(event: Name, ...args: Parameters<EventMap[Name]>): void {
    const handlers = this.listeners.get(event);

    if (!handlers) {
      return;
    }
    // Snapshot before calling: a handler is free to `off`/`once` itself or
    // register another one mid-dispatch without corrupting this pass.
    for (const handler of [...handlers]) {
      try {
        (handler as (...args: Parameters<EventMap[Name]>) => void)(...args);
      } catch (error) {
        // A handler throwing shouldn't stop delivery to the handlers after
        // it — one broken listener in the app shouldn't break the SDK's own
        // reporting to its other listeners.
        console.error(`[Emitter] handler for "${String(event)}" threw:`, error);
      }
    }
  }

  /** Drops every registered handler for every event. */
  clear(): void {
    this.listeners.clear();
  }
}
