package it.uhde.periph.chips.other;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * PCA9541 full interface — extends {@link PCA9541Minimal} with raw control
 * access, connect / disconnect, bus recovery, interrupt masks, the INT
 * functional test and interrupt delivery.
 */
public class PCA9541Full extends PCA9541Minimal {

    /** ISTAT / interrupt source bits. */
    public static final int SOURCE_INT_IN = 0x01;
    public static final int SOURCE_BUS_INIT = 0x02;
    public static final int SOURCE_BUS_NOT_IDLE = 0x04;
    public static final int SOURCE_BUS_LOST = 0x08;
    public static final int SOURCE_TEST = 0x40;
    public static final int SOURCE_OTHER_TEST = 0x80;

    /** Decoded CONTROL register. */
    public static final class Status {
        /** MYBUS == NMYBUS (control, regardless of the connection). */
        public final boolean hasControl;
        /** BUSON != NBUSON (some master is connected). */
        public final boolean connected;
        public final boolean busInitRequested;
        public final boolean ownTest;
        public final boolean otherTest;

        Status(boolean hasControl, boolean connected, boolean busInitRequested, boolean ownTest, boolean otherTest) {
            this.hasControl = hasControl;
            this.connected = connected;
            this.busInitRequested = busInitRequested;
            this.ownTest = ownTest;
            this.otherTest = otherTest;
        }
    }

    private volatile IntConsumer callback;
    private InputPin intPinUsed;
    private Thread pollThread;
    private volatile boolean polling;

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the device (0x70–0x77)
     * @throws IOException on bus error
     */
    public PCA9541Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /**
     * Read the raw CONTROL byte.
     *
     * @return CONTROL register value
     * @throws IOException on bus error
     */
    public int getControl() throws IOException {
        return readReg(REG_CONTROL);
    }

    /**
     * Whether the downstream bus is connected to some master (BUSON differs from NBUSON).
     *
     * @return true if connected
     * @throws IOException on bus error
     */
    public boolean isConnected() throws IOException {
        return connectedBits(readReg(REG_CONTROL));
    }

    /**
     * Decode the CONTROL register.
     *
     * @return the decoded status
     * @throws IOException on bus error
     */
    public Status status() throws IOException {
        int c = readReg(REG_CONTROL);
        return new Status(hasControlBits(c), connectedBits(c), (c & C_BUSINIT) != 0, (c & C_TESTON) != 0, (c & C_NTESTON) != 0);
    }

    /**
     * Turn the connection on without changing ownership (BUSON = NOT NBUSON; MYBUS as read).
     *
     * @throws IOException on bus error
     */
    public void connect() throws IOException {
        int c = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, ((1 - nbuson(c)) << 2) | (c & C_MYBUS));
    }

    /**
     * Disconnect both masters from the downstream bus (BUSON = NBUSON; ownership bits left).
     *
     * @throws IOException on bus error
     */
    public void disconnect() throws IOException {
        int c = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, (nbuson(c) << 2) | (c & C_MYBUS));
    }

    /**
     * Recover a hung downstream bus and take it (acquire with bus initialization).
     *
     * @param timeoutMs maximum time to wait for BUSINIT, in milliseconds
     * @return true if this master has control afterwards
     * @throws IOException on bus error
     */
    public boolean recoverBus(int timeoutMs) throws IOException {
        return acquire(true, timeoutMs);
    }

    /**
     * Write the IE register (true = masked, no interrupt); all-false re-enables every interrupt.
     *
     * @param busLost    mask the "other master took the bus" interrupt
     * @param busNotIdle mask the "bus non-idle at the switch" interrupt
     * @param busInit    mask the "bus initialization done" interrupt
     * @param intIn      mask the downstream INT_IN interrupt
     * @throws IOException on bus error
     */
    public void setInterruptMask(boolean busLost, boolean busNotIdle, boolean busInit, boolean intIn) throws IOException {
        writeReg(REG_IE, (busLost ? 8 : 0) | (busNotIdle ? 4 : 0) | (busInit ? 2 : 0) | (intIn ? 1 : 0));
    }

    /**
     * Read the IE register.
     *
     * @return IE value (bit set = interrupt masked)
     * @throws IOException on bus error
     */
    public int getInterruptMask() throws IOException {
        return readReg(REG_IE);
    }

    /**
     * Drive the INT lines for the functional test; the bus bits are written back unchanged.
     *
     * @param own   true to drive this master's INT low (TESTON)
     * @param other true to drive the other master's INT low (NTESTON)
     * @throws IOException on bus error
     */
    public void testInterrupt(boolean own, boolean other) throws IOException {
        int c = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, (c & (C_BUSON | C_MYBUS)) | (own ? C_TESTON : 0) | (other ? C_NTESTON : 0));
    }

    /**
     * Read IE, CONTROL and ISTAT in one auto-increment transaction (clears the self-clearing ISTAT flags).
     *
     * @return {@code {ie, control, istat}}
     * @throws IOException on bus error
     */
    public int[] readAll() throws IOException {
        byte[] b = connection.read(AI | REG_IE, 3);
        return new int[]{b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF};
    }

    /**
     * Enable an interrupt source (clear its mask bit in IE); the test sources need no enable.
     *
     * @param source one of {@link #SOURCE_INT_IN}, {@link #SOURCE_BUS_INIT}, {@link #SOURCE_BUS_NOT_IDLE}, {@link #SOURCE_BUS_LOST}
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        int bit = source & 0x0F;
        if (bit != 0) writeReg(REG_IE, readReg(REG_IE) & ~bit);
    }

    /**
     * Disable an interrupt source (set its mask bit in IE).
     *
     * @param source one of {@link #SOURCE_INT_IN}, {@link #SOURCE_BUS_INIT}, {@link #SOURCE_BUS_NOT_IDLE}, {@link #SOURCE_BUS_LOST}
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        int bit = source & 0x0F;
        if (bit != 0) writeReg(REG_IE, readReg(REG_IE) | bit);
    }

    /**
     * Read ISTAT (clears BUSLOST / BUSOK / BUSINIT).
     *
     * @return ISTAT byte; bits as the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return readReg(REG_ISTAT);
    }

    /**
     * Subscribe to this master's INT pin using {@code connection.intPin()}.
     *
     * @param callback called with the ISTAT byte that was read
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, null);
    }

    /**
     * Subscribe to this master's INT pin (falling edge). Falls back to a 100&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the ISTAT byte that was read
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    public synchronized void onInterrupt(IntConsumer callback, InputPin intPin) {
        this.callback = callback;
        startDelivery(intPin != null ? intPin : connection.intPin());
    }

    private void startDelivery(InputPin pin) {
        stopDelivery();
        intPinUsed = pin;
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "pca9541-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    private void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler);
            intPinUsed = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /** Unsubscribe and stop delivery. */
    public synchronized void offInterrupt() {
        stopDelivery();
        callback = null;
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (status != 0 && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
