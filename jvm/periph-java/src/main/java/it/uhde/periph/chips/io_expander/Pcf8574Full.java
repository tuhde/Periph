package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * PCF8574 full driver — extends {@link Pcf8574Minimal} with interrupt-on-change support.
 *
 * <p>Adds {@link #configureInterrupt(IntConsumer)} to start a 5 ms polling thread that
 * fires a callback whenever any input pin changes state, and {@link #clearInterrupt()} to
 * read the current pin states and return the bitmask of changed pins.
 *
 * <p>The INT output of the PCF8574 is active-low and open-drain. In this JVM driver,
 * interrupt delivery is always via a background polling thread; hardware GPIO edge
 * detection is not supported at the JVM level.
 */
public class Pcf8574Full extends Pcf8574Minimal {

    /** Port value from the previous read — used to detect changes. */
    private int prev = 0xFF;

    /** Callback invoked with the changed-pin bitmask on any input change. */
    private volatile IntConsumer callback;

    /** {@code true} while the polling thread is running. */
    private volatile boolean polling = false;

    /** Background daemon thread that polls the port every 5 ms. */
    private Thread pollThread;

    /**
     * Construct the full driver and initialise all pins to input mode.
     *
     * @param transport I²C transport bound to the PCF8574 device address
     * @throws IOException on I²C error
     */
    public Pcf8574Full(Transport transport) throws IOException {
        super(transport);
        prev = readPort();
    }

    // -------------------------------------------------------------------------
    // Interrupt API
    // -------------------------------------------------------------------------

    /**
     * Start a background polling thread that fires {@code callback} whenever any
     * input pin changes state.
     *
     * <p>The callback receives an 8-bit bitmask of pins that changed since the
     * previous read (bit n = 1 → pin n changed). The thread polls every 5 ms.
     * Call {@link #stopInterrupt()} to terminate the thread.
     *
     * @param callback {@link IntConsumer} invoked with the changed-pin bitmask
     */
    public void configureInterrupt(IntConsumer callback) {
        this.callback = callback;
        this.polling  = true;
        pollThread = new Thread(() -> {
            while (polling) {
                try {
                    int changed = clearInterrupt();
                    if (changed != 0 && this.callback != null) {
                        this.callback.accept(changed);
                    }
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException ignored) {
                    // transport error; keep polling
                }
            }
        }, "pcf8574-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /**
     * Stop the polling thread started by {@link #configureInterrupt(IntConsumer)}.
     *
     * <p>The thread is a daemon and will also stop automatically when the JVM exits.
     */
    public void stopInterrupt() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }

    /**
     * Read current pin states and return the bitmask of pins that changed since last read.
     *
     * <p>Reading the port also clears the chip's INT output. The previous-read value
     * is updated each time this method is called.
     *
     * @return 8-bit bitmask; bit n = 1 if pin n changed since the previous read
     * @throws IOException on I²C error
     */
    public int clearInterrupt() throws IOException {
        int current = readPort();
        int changed = current ^ prev;
        prev = current;
        return changed;
    }
}
