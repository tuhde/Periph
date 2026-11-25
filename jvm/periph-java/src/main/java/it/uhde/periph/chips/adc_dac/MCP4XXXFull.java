package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MCP4XXX family — full driver: extends {@link MCP4XXXMinimal} with ratio helpers, increment /
 * decrement, shutdown and terminal control, General Call, reset, raw access and — on the
 * non-volatile parts only (marked <b>NV</b>; they throw {@link UnsupportedOperationException} on the
 * volatile parts) — the EEPROM wiper, ten general-purpose EEPROM registers, STATUS, WiperLock and
 * write protect.
 */
public class MCP4XXXFull extends MCP4XXXMinimal {

    private static final int STATUS_EEWA = 0x08;
    private static final int[] STATUS_WL = {0x02, 0x04};
    private static final int STATUS_WP = 0x01;

    private final OutputPin hvc;
    private final int hvcSettleMs;

    /** Decoded STATUS register of the non-volatile parts. */
    public static final class Status {
        /** An EEPROM write cycle is in progress (EEWA). */
        public final boolean eepromBusy;
        /** WiperLock of network 0 and 1 (index 1 is false on single-network parts). */
        public final boolean[] wiperLock;
        /** Hardware WP pin OR the nonvolatile write-protect bit. */
        public final boolean writeProtect;

        Status(boolean eepromBusy, boolean[] wiperLock, boolean writeProtect) {
            this.eepromBusy = eepromBusy;
            this.wiperLock = wiperLock;
            this.writeProtect = writeProtect;
        }
    }

    /**
     * Construct the driver; performs no I²C traffic.
     *
     * @param connection  I²C connection bound to an address in the part's range
     * @param part        part name
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external circuit that drives HVC/A0 to 8.5–12.5 V
     *                    (needed only by {@link #setWiperLock} / {@link #setWriteProtect}); may be null
     * @param hvcSettleMs delay in ms between asserting {@code hvc} and the first SCL edge
     */
    public MCP4XXXFull(RegisterConnection connection, String part, double rAb, double rWiper,
                       OutputPin hvc, int hvcSettleMs) {
        super(connection, part, rAb, rWiper);
        this.hvc = desc.nonvolatile ? hvc : null;
        this.hvcSettleMs = hvcSettleMs;
    }

    /**
     * Construct without a high-voltage control pin.
     *
     * @param connection I²C connection
     * @param part       part name
     * @param rAb        RAB in ohms
     * @param rWiper     RW in ohms
     */
    public MCP4XXXFull(RegisterConnection connection, String part, double rAb, double rWiper) {
        this(connection, part, rAb, rWiper, null, 1);
    }

    /**
     * Construct with RAB = 10 kΩ and RW = 75 Ω.
     *
     * @param connection I²C connection
     * @param part       part name
     */
    public MCP4XXXFull(RegisterConnection connection, String part) {
        this(connection, part, 10000.0, 75.0);
    }

    /**
     * Construct for an MCP4532 with RAB = 10 kΩ and RW = 75 Ω.
     *
     * @param connection I²C connection
     */
    public MCP4XXXFull(RegisterConnection connection) {
        this(connection, "MCP4532");
    }

    private void requireNv() {
        if (!desc.nonvolatile) throw new UnsupportedOperationException("MCP4XXX: " + desc.name + " has no EEPROM");
    }

    private void requirePot() {
        if (!desc.potentiometer) {
            throw new UnsupportedOperationException("MCP4XXX: " + desc.name + " is a rheostat (terminal A not available)");
        }
    }

    /** @return number of resistor networks (1 or 2) */
    public int channels() { return desc.channels; }

    /** @return number of wiper positions (129 or 257) */
    public int steps() { return desc.maxCode + 1; }

    /** @return true for the X1 parts (terminal A on a pin) */
    public boolean isPotentiometer() { return desc.potentiometer; }

    /** @return true for the 454X / 456X / 464X / 466X parts */
    public boolean isNonvolatile() { return desc.nonvolatile; }

    /**
     * Set the wiper to a fraction of the full scale.
     *
     * @param ratio   0.0–1.0 (clamped)
     * @param channel network index
     * @throws IOException on I²C error
     */
    public void setRatio(double ratio, int channel) throws IOException {
        setWiper((int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * desc.maxCode), channel);
    }

    /**
     * Read the wiper position as a ratio.
     *
     * @param channel network index
     * @return code / maxCode
     * @throws IOException on I²C error
     */
    public double getRatio(int channel) throws IOException {
        return getWiper(channel) / (double) desc.maxCode;
    }

    /**
     * Read the W–A resistance (potentiometer parts only).
     *
     * @param channel network index
     * @return (maxCode − N) / maxCode × RAB + RW in ohms
     * @throws IOException on I²C error
     */
    public double getResistanceWa(int channel) throws IOException {
        requirePot();
        return (desc.maxCode - getWiper(channel)) / (double) desc.maxCode * rAb + rWiper;
    }

    /**
     * Set the W–A resistance to the nearest reachable value (potentiometer parts only).
     *
     * @param ohms    target wiper-to-A resistance in ohms
     * @param channel network index
     * @throws IOException on I²C error
     */
    public void setResistanceWa(double ohms, int channel) throws IOException {
        requirePot();
        checkChannel(channel);
        setWiper(desc.maxCode - (int) Math.round((ohms - rWiper) / rAb * desc.maxCode), channel);
    }

    /** @return resistance of one step, RAB / maxCode, in ohms */
    public double getResistanceStep() { return rAb / desc.maxCode; }

    /**
     * Write both wipers of a dual part (two transactions).
     *
     * @param code0 network 0 wiper position
     * @param code1 network 1 wiper position
     * @throws IOException on I²C error
     * @throws UnsupportedOperationException on a single-network part
     */
    public void setBoth(int code0, int code1) throws IOException {
        if (desc.channels < 2) throw new UnsupportedOperationException("MCP4XXX: " + desc.name + " has a single network");
        setWiper(code0, 0);
        setWiper(code1, 1);
    }

    private int step(int op, int steps, int channel) throws IOException {
        checkChannel(channel);
        if (steps > 0) {
            int command = ((ADDR_WIPER0 + channel) << 4) | op;
            // Continuous mode: one command byte per step, no data bytes, one transaction.
            byte[] extra = new byte[steps - 1];
            java.util.Arrays.fill(extra, (byte) command);
            connection.write(command, extra);
        }
        return readWiper(channel);
    }

    /**
     * Increment the wiper by {@code steps} in one continuous transaction (saturating at the max code).
     *
     * @param steps   number of increment commands
     * @param channel network index
     * @return new wiper code (read back)
     * @throws IOException on I²C error
     */
    public int increment(int steps, int channel) throws IOException {
        return step(OP_INC, steps, channel);
    }

    /**
     * Decrement the wiper by {@code steps} in one continuous transaction (saturating at 0).
     *
     * @param steps   number of decrement commands
     * @param channel network index
     * @return new wiper code (read back)
     * @throws IOException on I²C error
     */
    public int decrement(int steps, int channel) throws IOException {
        return step(OP_DEC, steps, channel);
    }

    private void modifyTcon(int[] bits, boolean[] on) throws IOException {
        int value = read9(ADDR_TCON);
        for (int i = 0; i < bits.length; i++) {
            value = on[i] ? (value | (1 << bits[i])) : (value & ~(1 << bits[i]));
        }
        write9(ADDR_TCON, value & 0x1FF);
    }

    /**
     * Shut a network down (TCON.RxHW = 0): A disconnected, W connected to B; the wiper is kept.
     *
     * @param channel network index
     * @throws IOException on I²C error
     */
    public void shutdown(int channel) throws IOException {
        checkChannel(channel);
        modifyTcon(new int[] {TCON_BITS[channel][0]}, new boolean[] {false});
    }

    /**
     * Leave shutdown (TCON.RxHW = 1).
     *
     * @param channel network index
     * @throws IOException on I²C error
     */
    public void resume(int channel) throws IOException {
        checkChannel(channel);
        modifyTcon(new int[] {TCON_BITS[channel][0]}, new boolean[] {true});
    }

    /**
     * Whether the network is in shutdown (read from TCON).
     *
     * @param channel network index
     * @return true if TCON.RxHW = 0
     * @throws IOException on I²C error
     */
    public boolean isShutdown(int channel) throws IOException {
        checkChannel(channel);
        return ((read9(ADDR_TCON) >> TCON_BITS[channel][0]) & 1) == 0;
    }

    /**
     * Connect or disconnect the A, W and B terminals of a network (TCON.RxA / RxW / RxB).
     *
     * @param a       connect terminal A
     * @param w       connect the wiper terminal
     * @param b       connect terminal B
     * @param channel network index
     * @throws IOException on I²C error
     */
    public void setTerminals(boolean a, boolean w, boolean b, int channel) throws IOException {
        checkChannel(channel);
        int[] bits = TCON_BITS[channel];
        modifyTcon(new int[] {bits[1], bits[2], bits[3]}, new boolean[] {a, w, b});
    }

    /**
     * Read the terminal connection state of a network from TCON.
     *
     * @param channel network index
     * @return {@code {a, w, b}}
     * @throws IOException on I²C error
     */
    public boolean[] getTerminals(int channel) throws IOException {
        checkChannel(channel);
        int tcon = read9(ADDR_TCON);
        int[] bits = TCON_BITS[channel];
        return new boolean[] {((tcon >> bits[1]) & 1) == 1, ((tcon >> bits[2]) & 1) == 1, ((tcon >> bits[3]) & 1) == 1};
    }

    /**
     * Read the raw 9-bit TCON register.
     *
     * @return TCON value
     * @throws IOException on I²C error
     */
    public int getTcon() throws IOException {
        return read9(ADDR_TCON);
    }

    /**
     * Enable or disable the General Call address (TCON.GCEN).
     *
     * @param enabled true to answer General Call (address 0x00) commands
     * @throws IOException on I²C error
     */
    public void setGeneralCall(boolean enabled) throws IOException {
        modifyTcon(new int[] {8}, new boolean[] {enabled});
    }

    /**
     * Emulate a power-on reset: write every volatile wiper with its power-up value (mid-scale, or the
     * EEPROM wiper on non-volatile parts) and TCON 0x1FF; networks locked by WiperLock are skipped.
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        boolean[] locked = desc.nonvolatile ? readStatus().wiperLock : new boolean[2];
        boolean any = false;
        for (int c = 0; c < desc.channels; c++) {
            if (locked[c]) {
                any = true;
                continue;
            }
            setWiper(desc.nonvolatile ? getNvWiper(c) : desc.maxCode / 2, c);
        }
        if (!any) write9(ADDR_TCON, TCON_DEFAULT);
    }

    private void checkAddress(int address, boolean writable) {
        boolean ok = address == ADDR_TCON || address < desc.channels;
        if (desc.nonvolatile) {
            ok = ok || ((address == 2 || address == 3) && address - 2 < desc.channels) || (address >= ADDR_DATA0 && address <= 0x0F);
            if (address == ADDR_STATUS) ok = !writable;
        }
        if (address < 0 || address > 0x0F || !ok) {
            throw new IllegalArgumentException(String.format("MCP4XXX: address 0x%02X not available on %s", address, desc.name));
        }
    }

    /**
     * Raw 9-bit read of a memory address the part has.
     *
     * @param address memory address 0–15
     * @return 9-bit value
     * @throws IOException on I²C error
     */
    public int readRegister(int address) throws IOException {
        checkAddress(address, false);
        return read9(address);
    }

    /**
     * Raw write of a writable memory address the part has.
     *
     * @param address memory address
     * @param value   value (0–0x3FF for wipers, 0–0x1FF otherwise)
     * @throws IOException on I²C error
     */
    public void writeRegister(int address, int value) throws IOException {
        checkAddress(address, true);
        write9(address, Math.max(0, Math.min(address < 2 ? 0x3FF : 0x1FF, value)));
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     *
     * @param rAb    end-to-end resistance RAB in ohms
     * @param rWiper wiper resistance RW in ohms; pass a negative value to keep the current RW
     */
    public void setRab(double rAb, double rWiper) {
        this.rAb = rAb;
        if (rWiper >= 0) this.rWiper = rWiper;
    }

    /**
     * <b>NV.</b> Read and decode STATUS.
     *
     * @return the decoded status
     * @throws IOException on I²C error
     */
    public Status readStatus() throws IOException {
        requireNv();
        int v = read9(ADDR_STATUS);
        boolean[] wl = new boolean[2];
        for (int c = 0; c < desc.channels; c++) wl[c] = (v & STATUS_WL[c]) != 0;
        return new Status((v & STATUS_EEWA) != 0, wl, (v & STATUS_WP) != 0);
    }

    /**
     * <b>NV.</b> Poll STATUS.EEWA until the EEPROM write cycle has finished (tWC is 5 ms typical, 10 ms maximum).
     *
     * @param timeoutMs maximum wait in milliseconds
     * @throws IOException on I²C error or if the write cycle does not finish in time
     */
    public void waitReady(int timeoutMs) throws IOException {
        requireNv();
        for (int i = 0; i <= timeoutMs; i++) {
            if ((read9(ADDR_STATUS) & STATUS_EEWA) == 0) return;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("MCP4XXX: interrupted while waiting for the EEPROM", e);
            }
        }
        throw new IOException("MCP4XXX: EEPROM write did not finish within " + timeoutMs + " ms");
    }

    /**
     * <b>NV.</b> Read the EEPROM wiper (power-up value).
     *
     * @param channel network index
     * @return stored code, clamped to the max code
     * @throws IOException on I²C error
     */
    public int getNvWiper(int channel) throws IOException {
        requireNv();
        checkChannel(channel);
        return Math.min(read9(ADDR_NVWIPER0 + channel), desc.maxCode);
    }

    /**
     * <b>NV.</b> Write the EEPROM wiper (the volatile wiper is not changed) and wait for the write cycle.
     *
     * @param code    power-up code (clamped)
     * @param channel network index
     * @throws IOException on I²C error or timeout
     */
    public void setNvWiper(int code, int channel) throws IOException {
        requireNv();
        checkChannel(channel);
        write9(ADDR_NVWIPER0 + channel, clamp(code));
        waitReady(20);
    }

    /**
     * <b>NV.</b> Write the current volatile wiper to the EEPROM wiper.
     *
     * @param channel network index
     * @return the stored code
     * @throws IOException on I²C error or timeout
     */
    public int storeWiper(int channel) throws IOException {
        requireNv();
        int code = getWiper(channel);
        setNvWiper(code, channel);
        return code;
    }

    /**
     * <b>NV.</b> Read general-purpose EEPROM register {@code index}.
     *
     * @param index register index 0–9
     * @return 9-bit value
     * @throws IOException on I²C error
     */
    public int readData(int index) throws IOException {
        requireNv();
        if (index < 0 || index > 9) throw new IllegalArgumentException("MCP4XXX: data index must be 0-9");
        return read9(ADDR_DATA0 + index);
    }

    /**
     * <b>NV.</b> Write general-purpose EEPROM register {@code index} (uses one write cycle).
     *
     * @param index register index 0–9
     * @param value 9-bit value 0–0x1FF
     * @throws IOException on I²C error or timeout
     */
    public void writeData(int index, int value) throws IOException {
        requireNv();
        if (index < 0 || index > 9) throw new IllegalArgumentException("MCP4XXX: data index must be 0-9");
        write9(ADDR_DATA0 + index, Math.max(0, Math.min(0x1FF, value)));
        waitReady(20);
    }

    /**
     * <b>NV.</b> Whether WiperLock is active for a network (STATUS.WLx).
     *
     * @param channel network index
     * @return true if locked
     * @throws IOException on I²C error
     */
    public boolean isWiperLocked(int channel) throws IOException {
        checkChannel(channel);
        return readStatus().wiperLock[channel];
    }

    /**
     * <b>NV.</b> Whether the EEPROM is write protected (hardware WP pin OR the nonvolatile bit).
     *
     * @return STATUS.WP
     * @throws IOException on I²C error
     */
    public boolean isWriteProtected() throws IOException {
        return readStatus().writeProtect;
    }

    private void hvCommand(int address, boolean decrement) throws IOException {
        if (hvc == null) throw new UnsupportedOperationException("MCP4XXX: a high-voltage command needs the hvc pin");
        int command = (address << 4) | (decrement ? OP_DEC : OP_INC);
        hvc.set(true);
        try {
            Thread.sleep(hvcSettleMs);
            connection.write(command, new byte[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP4XXX: interrupted during the high-voltage command", e);
        } finally {
            hvc.set(false);
        }
        waitReady(20);
    }

    /**
     * <b>NV, needs the hvc pin.</b> Enable (decrement command) or disable (increment) WiperLock of a
     * network with a high-voltage command; waits for the EEPROM write cycle that stores the bit.
     *
     * @param locked  true to lock, false to unlock
     * @param channel network index
     * @throws IOException on I²C error or timeout
     */
    public void setWiperLock(boolean locked, int channel) throws IOException {
        requireNv();
        checkChannel(channel);
        hvCommand(ADDR_NVWIPER0 + channel, locked);
    }

    /**
     * <b>NV, needs the hvc pin.</b> Set or clear the nonvolatile EEPROM write-protect bit with a
     * high-voltage command; the hardware WP pin (MCP4641 / MCP4661) can still block writes.
     *
     * @param enabled true to enable write protect, false to disable
     * @throws IOException on I²C error or timeout
     */
    public void setWriteProtect(boolean enabled) throws IOException {
        requireNv();
        hvCommand(ADDR_WP, enabled);
    }
}
