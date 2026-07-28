package it.uhde.periph.connection;

/** Drives a chip's EN (hardware enable/power) pin. */
public interface OutputPin extends AutoCloseable {

    /**
     * Set the pin's output level.
     *
     * @param high {@code true} drives the pin high, {@code false} drives it low
     */
    void set(boolean high);

    @Override
    void close();
}
