package it.uhde.periph.connection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link OutputPin} backed by the Linux sysfs GPIO interface
 * ({@code /sys/class/gpio/gpioN/...}).
 *
 * <p>The GPIO must already be exported (e.g. {@code echo N > /sys/class/gpio/export})
 * and owned by the current user; this class does not export or unexport it, only
 * configures direction and writes the value.
 */
public final class SysfsOutputPin implements OutputPin {

    private final Path valuePath;

    /**
     * @param gpioNumber sysfs GPIO number (the {@code N} in {@code /sys/class/gpio/gpioN})
     * @throws UncheckedIOException if the direction cannot be set to {@code out}
     */
    public SysfsOutputPin(int gpioNumber) {
        Path base = Path.of("/sys/class/gpio/gpio" + gpioNumber);
        this.valuePath = base.resolve("value");
        try {
            Files.writeString(base.resolve("direction"), "out");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void set(boolean high) {
        try {
            Files.writeString(valuePath, high ? "1" : "0");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** No-op — this class does not own the GPIO export lifecycle. */
    @Override
    public void close() {}
}
