package it.uhde.periph.connection;

/**
 * Delivers edge notifications from a chip's INT line.
 *
 * <p>Intentionally minimal: it only signals that <em>an</em> edge occurred. The
 * chip driver always calls {@code pollInterrupt()} to determine the cause.
 * Multiple handlers may be registered on the same {@link InputPin} instance —
 * the common pattern for chips with open-drain INT outputs wired to a single
 * GPIO.
 */
public interface InputPin extends AutoCloseable {

    /**
     * Register {@code handler} to run on each edge matching {@code trigger}.
     *
     * @param handler zero-argument callback invoked on each matching edge
     * @param trigger which edge(s) to deliver
     */
    void onEdge(EdgeHandler handler, EdgeTrigger trigger);

    /**
     * Remove a specific handler. No-op if not registered.
     *
     * @param handler the exact instance previously passed to {@link #onEdge}
     */
    void offEdge(EdgeHandler handler);

    @Override
    void close();
}
