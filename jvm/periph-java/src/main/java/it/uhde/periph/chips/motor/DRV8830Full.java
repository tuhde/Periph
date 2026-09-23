package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * DRV8830 full interface — extends {@link DRV8830Minimal} with raw
 * {@code CONTROL} access, output read-back, fault reporting/clearing, and the
 * {@code FAULTn} interrupt API.
 *
 * <p>Faults are never cleared implicitly: a latched OCP/ILIMIT fault also
 * disables the H-bridge, so clearing is always an explicit
 * {@link #clearFault()}. {@link #onInterrupt(Consumer)} uses
 * {@code connection.intPin()} if wired, otherwise a 5&nbsp;ms polling thread
 * that fires once per new fault.
 */
public class DRV8830Full extends DRV8830Minimal {

    /** H-bridge state decoded from {@code IN1}/{@code IN2}. */
    public enum Direction {
        /** IN1=0, IN2=0 — outputs high-Z (standby). */
        COAST,
        /** IN1=1, IN2=0. */
        FORWARD,
        /** IN1=0, IN2=1. */
        REVERSE,
        /** IN1=1, IN2=1 — both outputs high. */
        BRAKE
    }

    /**
     * Decoded {@code CONTROL} register.
     *
     * @param voltage   commanded magnitude in V (0 for a reserved {@code VSET} code)
     * @param direction H-bridge state
     */
    public record Output(double voltage, Direction direction) {}

    /**
     * Decoded {@code FAULT} register.
     *
     * @param fault  any fault condition exists
     * @param ocp    overcurrent (short-circuit) event
     * @param uvlo   undervoltage lockout
     * @param ots    overtemperature shutdown
     * @param ilimit extended current-limit event
     */
    public record Fault(boolean fault, boolean ocp, boolean uvlo, boolean ots, boolean ilimit) {}

    private volatile Consumer<Fault> callback;
    private InputPin intPin;
    private volatile boolean polling = false;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver; same presence check as {@link DRV8830Minimal}.
     *
     * @param connection configured I²C connection bound to the device (0x60–0x68)
     * @throws IOException on bus error
     */
    public DRV8830Full(Connection connection) throws IOException {
        super(connection);
    }

    /**
     * Write the {@code CONTROL} register from raw fields.
     *
     * @param vset {@code VSET} DAC code, 6–63 (0–5 are reserved)
     * @param in1  H-bridge input 1
     * @param in2  H-bridge input 2
     * @throws IllegalArgumentException if {@code vset} is outside 6–63
     * @throws IOException on bus error
     */
    public void setOutput(int vset, boolean in1, boolean in2) throws IOException {
        if (vset < VSET_MIN || vset > VSET_MAX) {
            throw new IllegalArgumentException("vset must be 6-63, got " + vset);
        }
        writeReg(REG_CONTROL, (vset << 2) | (in1 ? CTRL_IN1 : 0) | (in2 ? CTRL_IN2 : 0));
    }

    /**
     * Read back and decode the {@code CONTROL} register.
     *
     * @return commanded voltage magnitude and H-bridge direction
     * @throws IOException on bus error
     */
    public Output readOutput() throws IOException {
        int ctrl = readReg(REG_CONTROL);
        return new Output(vsetToVoltage(ctrl >> 2), Direction.values()[ctrl & 0x03]);
    }

    /**
     * Read the {@code FAULT} register without clearing it.
     *
     * @return decoded fault flags
     * @throws IOException on bus error
     */
    public Fault readFault() throws IOException {
        int f = readReg(REG_FAULT);
        return new Fault((f & FAULT_FAULT) != 0, (f & FAULT_OCP) != 0, (f & FAULT_UVLO) != 0,
                (f & FAULT_OTS) != 0, (f & FAULT_ILIMIT) != 0);
    }

    /**
     * Clear all fault status bits ({@code CLEAR} = 1); re-enables the H-bridge
     * if an OCP/ILIMIT fault had latched it off.
     *
     * @throws IOException on bus error
     */
    public void clearFault() throws IOException {
        writeReg(REG_FAULT, FAULT_CLEAR);
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 1 — FAULTn)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to fault interrupts using {@code connection.intPin()} (or a
     * 5&nbsp;ms polling thread if none is wired). The fault is not cleared.
     *
     * @param callback called with the {@link #readFault()} result on each new fault
     */
    public void onInterrupt(Consumer<Fault> callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to fault interrupts, overriding which {@link InputPin} delivers edges.
     *
     * @param callback called with the {@link #readFault()} result on each new fault
     * @param intPin   FAULTn pin to arm (falling edge), or {@code null} to force the 5&nbsp;ms polling fallback
     */
    public void onInterrupt(Consumer<Fault> callback, InputPin intPin) {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            this.intPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            startPolling();
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        stopPolling();
    }

    /**
     * Read the fault status — equivalent to {@link #readFault()}, does not clear.
     *
     * @return decoded fault flags
     * @throws IOException on bus error
     */
    public Fault pollInterrupt() throws IOException {
        return readFault();
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            boolean wasFault = false;
            while (polling) {
                try {
                    Fault status = pollInterrupt();
                    Consumer<Fault> cb = callback;
                    if (status.fault() && !wasFault && cb != null) cb.accept(status);
                    wasFault = status.fault();
                } catch (IOException ignored) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "drv8830-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        try {
            Fault status = pollInterrupt();
            Consumer<Fault> cb = callback;
            if (status.fault() && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
