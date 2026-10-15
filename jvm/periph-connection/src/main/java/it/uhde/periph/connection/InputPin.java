package it.uhde.periph.connection;

import java.io.IOException;

/**
 * Delivers edge notifications from a chip's INT line, and reads its level.
 *
 * <p>Intentionally minimal: it only signals that <em>an</em> edge occurred. The
 * chip driver always calls {@code pollInterrupt()} to determine the cause.
 * Multiple handlers may be registered on the same {@link InputPin} instance —
 * the common pattern for chips with open-drain INT outputs wired to a single
 * GPIO.
 *
 * <p>Level signals a driver samples at a moment of its own choosing (a BUSY
 * output, a data-ready line) use {@link #isHigh()} instead of an edge handler.
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

    /**
     * Read the electrical level of the line now.
     *
     * <p>The raw level: no inversion and no debounce — active-low signals are
     * interpreted by the chip driver. A level read does not disturb registered
     * edge handlers.
     *
     * @return {@code true} when the line is high
     * @throws IOException if the line cannot be read
     */
    boolean isHigh() throws IOException;

    @Override
    void close();
}
