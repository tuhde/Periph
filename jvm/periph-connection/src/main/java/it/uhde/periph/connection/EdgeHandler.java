package it.uhde.periph.connection;

/** Zero-argument callback invoked by {@link InputPin} on a matching edge. */
@FunctionalInterface
public interface EdgeHandler {
    void onEdge();
}
