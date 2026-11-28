package it.uhde.periph.chips.rfid

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.function.IntConsumer

/**
 * ST25DV full driver — extends {@link St25dvMinimal} with identification, system area, password
 * session, memory areas, GPO interrupts, energy harvesting, RF state, fast-transfer-mode mailbox
 * and a minimal NDEF writer.
 *
 * <p>System-area writes need an open I²C security session ({@link #presentPassword(byte[])}).
 * Bits 0-6 of {@code GPO_CTRL_Dyn} are read-only over I²C, so interrupt sources are configured in
 * the static {@code GPO} register (session required); only {@code GPO_EN} switches at runtime
 * without a session.
 */
@CompileStatic
class St25dvFull extends St25dvMinimal {

    /** Interrupt source (IT_STS_Dyn bit 0): RF "Manage GPO" set/reset. */
    public static final int SOURCE_RF_USER = 0x01;
    /** Interrupt source (bit 1): RF access. */
    public static final int SOURCE_RF_ACTIVITY = 0x02;
    /** Interrupt source (bit 2): RF "Manage GPO" interrupt request. */
    public static final int SOURCE_RF_INTERRUPT = 0x04;
    /** Interrupt source (bit 3): RF field disappeared. */
    public static final int SOURCE_FIELD_FALLING = 0x08;
    /** Interrupt source (bit 4): RF field appeared. */
    public static final int SOURCE_FIELD_RISING = 0x10;
    /** Interrupt source (bit 5): RF reader wrote a mailbox message. */
    public static final int SOURCE_RF_PUT_MSG = 0x20;
    /** Interrupt source (bit 6): RF reader read the mailbox message. */
    public static final int SOURCE_RF_GET_MSG = 0x40;
    /** Interrupt source (bit 7): RF reader wrote to EEPROM. */
    public static final int SOURCE_RF_WRITE = 0x80;

    /** GPO bit 0: GPO level by RF "Manage GPO" set/reset. */
    public static final int GPO_RF_USER = 0x01;
    /** GPO bit 1: GPO follows RF activity. */
    public static final int GPO_RF_ACTIVITY = 0x02;
    /** GPO bit 2: GPO pulse by RF "Manage GPO". */
    public static final int GPO_RF_INTERRUPT = 0x04;
    /** GPO bit 3: pulse on RF field change. */
    public static final int GPO_FIELD_CHANGE = 0x08;
    /** GPO bit 4: pulse when the reader put a mailbox message. */
    public static final int GPO_RF_PUT_MSG = 0x10;
    /** GPO bit 5: pulse when the reader got the mailbox message. */
    public static final int GPO_RF_GET_MSG = 0x20;
    /** GPO bit 6: pulse after an RF EEPROM write. */
    public static final int GPO_RF_WRITE = 0x40;
    /** GPO bit 7: GPO output enable. */
    public static final int GPO_EN = 0x80;

    protected static final int SYS_GPO = 0x0000;
    protected static final int SYS_IT_TIME = 0x0001;
    protected static final int SYS_EH_MODE = 0x0002;
    protected static final int SYS_RFA1SS = 0x0004;
    protected static final int SYS_ENDA1 = 0x0005;
    protected static final int SYS_I2CSS = 0x000B;
    protected static final int SYS_LOCK_CCFILE = 0x000C;
    protected static final int SYS_MB_MODE = 0x000D;
    protected static final int SYS_MB_WDG = 0x000E;
    protected static final int SYS_UID = 0x0018;
    protected static final int SYS_IC_REV = 0x0020;
    protected static final int SYS_I2C_PWD = 0x0900;
    protected static final int DYN_GPO_CTRL = 0x2000;
    protected static final int DYN_EH_CTRL = 0x2002;
    protected static final int DYN_RF_MNGT = 0x2003;
    protected static final int DYN_I2C_SSO = 0x2004;
    protected static final int DYN_IT_STS = 0x2005;
    protected static final int DYN_MB_CTRL = 0x2006;
    protected static final int DYN_MB_LEN = 0x2007;
    protected static final int MAILBOX = 0x2008;
    protected static final int MAILBOX_SIZE = 256;

    /**
     * Decoded {@code MB_CTRL_Dyn}.
     *
     * @param enabled     MB_EN: fast transfer mode active
     * @param hostPut     HOST_PUT_MSG: the host wrote the current message
     * @param rfPut       RF_PUT_MSG: the RF reader wrote the current message
     * @param hostMiss    HOST_MISS_MSG: host message not read before the watchdog
     * @param rfMiss      RF_MISS_MSG: RF message not read before the watchdog
     * @param hostCurrent HOST_CURRENT_MSG: the host is reading the message
     * @param rfCurrent   RF_CURRENT_MSG: the RF reader is reading the message
     */
    static record MailboxStatus(boolean enabled, boolean hostPut, boolean rfPut, boolean hostMiss,
                                boolean rfMiss, boolean hostCurrent, boolean rfCurrent) {}

    /**
     * Protection of one memory area.
     *
     * @param rf  RFAnSS value 0-15
     * @param i2c I2CSS level 0-3
     */
    static record AreaProtection(int rf, int i2c) {}

    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct with the -IE default (open-drain, active-low GPO).
     *
     * @param user   connection at 0x53 ({@code regBytes} = 2)
     * @param system connection at 0x57 ({@code regBytes} = 2)
     * @throws IOException on I²C error
     */
    public St25dvFull(RegisterConnection user, RegisterConnection system) throws IOException {
        super(user, system, false);
    }

    /**
     * Construct the driver and read the identification.
     *
     * @param user          connection at 0x53 ({@code regBytes} = 2)
     * @param system        connection at 0x57 ({@code regBytes} = 2)
     * @param gpoActiveHigh true for -JF variants (CMOS GPO, rising edge)
     * @throws IOException on I²C error
     */
    public St25dvFull(RegisterConnection user, RegisterConnection system, boolean gpoActiveHigh) throws IOException {
        super(user, system, gpoActiveHigh);
    }

    /** @return user memory size in bytes ((MEM_SIZE + 1) × (BLK_SIZE + 1)) */
    public int memorySize() { return size; }

    /** @return IC_REF: 0x24 (ST25DV04K) or 0x26 (ST25DV16K/64K) */
    public int icReference() { return icRef; }

    /**
     * Read the 64-bit unique ID.
     *
     * @return 8 bytes, most significant first (E0 02 …)
     * @throws IOException on I²C error
     */
    public byte[] uid() throws IOException {
        byte[] raw = readReg(system, SYS_UID, 8);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = raw[7 - i];
        return out;
    }

    /**
     * Read IC_REV.
     *
     * @return IC revision
     * @throws IOException on I²C error
     */
    public int icRevision() throws IOException { return sysByte(SYS_IC_REV); }

    /**
     * Raw read of the system configuration area (device 0x57).
     *
     * @param address system register address
     * @param length  number of bytes
     * @return the bytes read
     * @throws IOException on I²C error
     */
    public byte[] readSystem(int address, int length) throws IOException {
        return readReg(system, address, length);
    }

    /**
     * Raw write to the system area, then ACK poll (session required).
     *
     * @param address system register address
     * @param data    bytes to write
     * @throws IOException if NACKed (no session, locked) or the write cycle does not complete
     */
    public void writeSystem(int address, byte[] data) throws IOException {
        system.write(address, data);
        commit(system);
    }

    /**
     * Write one system register byte (session required).
     *
     * @param address system register address
     * @param value   byte value
     * @throws IOException on I²C error
     */
    public void writeSystem(int address, int value) throws IOException {
        writeSystem(address, new byte[]{(byte) value});
    }

    private int sysByte(int reg) throws IOException { return readReg(system, reg, 1)[0] & 0xFF; }

    private int dynByte(int reg) throws IOException { return readReg(user, reg, 1)[0] & 0xFF; }

    private void writeDyn(int reg, int value) throws IOException { user.write(reg, new byte[]{(byte) value}); }

    private static byte[] passwordFrame(byte[] password, int code) {
        if (password.length != 8) throw new IllegalArgumentException("password must be 8 bytes");
        byte[] f = new byte[17];
        System.arraycopy(password, 0, f, 0, 8);
        f[8] = (byte) code;
        System.arraycopy(password, 0, f, 9, 8);
        return f;
    }

    /**
     * Present the I²C password (opens the security session).
     *
     * @param password 8 bytes, most significant first (factory 00 × 8)
     * @return true if the session is open afterwards
     * @throws IOException on I²C error
     */
    public boolean presentPassword(byte[] password) throws IOException {
        system.write(SYS_I2C_PWD, passwordFrame(password, 0x09));
        ackPoll(system, WRITE_TIMEOUT_MS);
        return sessionOpen();
    }

    /**
     * Change the I²C password (session must be open).
     *
     * @param password new 8-byte password, most significant first
     * @throws IOException if no session is open or the write fails
     */
    public void writePassword(byte[] password) throws IOException {
        byte[] frame = passwordFrame(password, 0x07);
        if (!sessionOpen()) throw new IOException("ST25DV security session is closed");
        system.write(SYS_I2C_PWD, frame);
        commit(system);
    }

    /**
     * I2C_SSO_Dyn bit 0.
     *
     * @return true if the I²C security session is open
     * @throws IOException on I²C error
     */
    public boolean sessionOpen() throws IOException { return (dynByte(DYN_I2C_SSO) & 0x01) != 0; }

    /**
     * Set ENDA1..3 (session required); area n ends at byte endBlock × 32 + 31.
     *
     * @param area     1-3
     * @param endBlock ENDAn value 0-255
     * @throws IOException on I²C error
     */
    public void setAreaEnd(int area, int endBlock) throws IOException {
        if (area < 1 || area > 3) throw new IllegalArgumentException("area must be 1-3");
        if (endBlock < 0 || endBlock > 0xFF) throw new IllegalArgumentException("endBlock must be 0-255");
        writeSystem(SYS_ENDA1 + 2 * (area - 1), endBlock);
    }

    /**
     * Set the RF and I²C protection of an area (session required).
     *
     * @param area 1-4
     * @param rf   RFAnSS 0-15 (bits 3:2 RW protection, bits 1:0 RF password)
     * @param i2c  I2CSS level 0-3 (bit 0 write, bit 1 read protection)
     * @throws IOException on I²C error
     */
    public void setAreaProtection(int area, int rf, int i2c) throws IOException {
        if (area < 1 || area > 4) throw new IllegalArgumentException("area must be 1-4");
        if (rf < 0 || rf > 0x0F || i2c < 0 || i2c > 3) throw new IllegalArgumentException("rf must be 0-15 and i2c 0-3");
        writeSystem(SYS_RFA1SS + 2 * (area - 1), rf);
        int shift = 2 * (area - 1);
        int i2css = (sysByte(SYS_I2CSS) & ~(0x03 << shift) & 0xFF) | (i2c << shift);
        writeSystem(SYS_I2CSS, i2css);
    }

    /**
     * Read the protection of an area.
     *
     * @param area 1-4
     * @return RFAnSS &amp; 0x0F and the area's I2CSS level
     * @throws IOException on I²C error
     */
    public AreaProtection getAreaProtection(int area) throws IOException {
        if (area < 1 || area > 4) throw new IllegalArgumentException("area must be 1-4");
        int rf = sysByte(SYS_RFA1SS + 2 * (area - 1)) & 0x0F;
        int i2c = (sysByte(SYS_I2CSS) >> (2 * (area - 1))) & 0x03;
        return new AreaProtection(rf, i2c);
    }

    /**
     * Write the static GPO register (session required); the chip copies it into GPO_CTRL_Dyn.
     *
     * @param sources mask of GPO_* enable bits 0-6
     * @param enable  sets GPO_EN
     * @throws IOException on I²C error
     */
    public void setGpo(int sources, boolean enable) throws IOException {
        writeSystem(SYS_GPO, (sources & 0x7F) | (enable ? GPO_EN : 0));
    }

    /**
     * Write the static GPO register with GPO_EN set.
     *
     * @param sources mask of GPO_* enable bits 0-6
     * @throws IOException on I²C error
     */
    public void setGpo(int sources) throws IOException { setGpo(sources, true); }

    /**
     * Read the static GPO register.
     *
     * @return GPO_* mask
     * @throws IOException on I²C error
     */
    public int getGpo() throws IOException { return sysByte(SYS_GPO); }

    /**
     * Switch the GPO output at runtime (GPO_CTRL_Dyn.GPO_EN, no session; bits 0-6 are read-only).
     *
     * @param enable true enables the output
     * @throws IOException on I²C error
     */
    public void setGpoRuntime(boolean enable) throws IOException { writeDyn(DYN_GPO_CTRL, enable ? GPO_EN : 0); }

    /**
     * Set IT_TIME (session required); pulse = 301 µs − value × 37.65 µs.
     *
     * @param value 0-7
     * @throws IOException on I²C error
     */
    public void setInterruptPulse(int value) throws IOException {
        if (value < 0 || value > 7) throw new IllegalArgumentException("value must be 0-7");
        writeSystem(SYS_IT_TIME, value);
    }

    /**
     * EH_CTRL_Dyn.FIELD_ON.
     *
     * @return true if an RF field is present
     * @throws IOException on I²C error
     */
    public boolean fieldPresent() throws IOException { return (dynByte(DYN_EH_CTRL) & 0x04) != 0; }

    /**
     * Set EH_CTRL_Dyn.EH_EN; write EH_MODE (session required) only when {@code forced} changes it.
     *
     * @param enabled energy harvesting on now
     * @param forced  forced after power-up (EH_MODE = 0) instead of on demand
     * @throws IOException on I²C error
     */
    public void setEnergyHarvesting(boolean enabled, boolean forced) throws IOException {
        int mode = forced ? 0 : 1;
        if ((sysByte(SYS_EH_MODE) & 0x01) != mode) writeSystem(SYS_EH_MODE, mode);
        writeDyn(DYN_EH_CTRL, enabled ? 0x01 : 0x00);
    }

    /**
     * Set EH_CTRL_Dyn.EH_EN with the on-demand EH_MODE default.
     *
     * @param enabled energy harvesting on now
     * @throws IOException on I²C error
     */
    public void setEnergyHarvesting(boolean enabled) throws IOException { setEnergyHarvesting(enabled, false); }

    /**
     * Set the runtime RF state RF_MNGT_Dyn (the static RF_MNGT is unchanged).
     *
     * @param disable RF_DISABLE
     * @param sleep   RF_SLEEP
     * @throws IOException on I²C error
     */
    public void setRfState(boolean disable, boolean sleep) throws IOException {
        writeDyn(DYN_RF_MNGT, (disable ? 0x01 : 0) | (sleep ? 0x02 : 0));
    }

    /**
     * Set MB_MODE if needed (session required), then MB_CTRL_Dyn.MB_EN.
     *
     * @throws IOException on I²C error
     */
    public void enableMailbox() throws IOException {
        if ((sysByte(SYS_MB_MODE) & 0x01) == 0) writeSystem(SYS_MB_MODE, 0x01);
        writeDyn(DYN_MB_CTRL, 0x01);
    }

    /**
     * Clear MB_CTRL_Dyn.MB_EN (MB_MODE stays).
     *
     * @throws IOException on I²C error
     */
    public void disableMailbox() throws IOException { writeDyn(DYN_MB_CTRL, 0x00); }

    /**
     * Decode MB_CTRL_Dyn.
     *
     * @return the mailbox status
     * @throws IOException on I²C error
     */
    public MailboxStatus mailboxStatus() throws IOException {
        int v = dynByte(DYN_MB_CTRL);
        return new MailboxStatus((v & 0x01) != 0, (v & 0x02) != 0, (v & 0x04) != 0, (v & 0x10) != 0,
                (v & 0x20) != 0, (v & 0x40) != 0, (v & 0x80) != 0);
    }

    /**
     * Put a message into the mailbox (one write starting at 0x2008).
     *
     * @param data 1-256 bytes
     * @throws IOException if the mailbox is disabled, busy or the write is NACKed
     */
    public void mailboxWrite(byte[] data) throws IOException {
        if (data.length < 1 || data.length > MAILBOX_SIZE) throw new IllegalArgumentException("mailbox message must be 1-256 bytes");
        int ctrl = dynByte(DYN_MB_CTRL);
        if ((ctrl & 0x01) == 0) throw new IOException("ST25DV mailbox is disabled");
        if ((ctrl & 0x06) != 0) throw new IOException("ST25DV mailbox is busy");
        user.write(MAILBOX, data);
    }

    /**
     * Read the mailbox message.
     *
     * @return MB_LEN_Dyn + 1 bytes, or empty when no message is present
     * @throws IOException on I²C error
     */
    public byte[] mailboxRead() throws IOException {
        int ctrl = dynByte(DYN_MB_CTRL);
        if ((ctrl & 0x06) == 0) return new byte[0];
        int length = dynByte(DYN_MB_LEN) + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        for (int off = 0; off < length; off += CHUNK) {
            out.writeBytes(readReg(user, MAILBOX + off, Math.min(CHUNK, length - off)));
        }
        return out.toByteArray();
    }

    /**
     * Set MB_WDG (session required); watchdog = 2^(value − 1) × 30 ms, 0 disables it.
     *
     * @param value 0-7
     * @throws IOException on I²C error
     */
    public void setMailboxWatchdog(int value) throws IOException {
        if (value < 0 || value > 7) throw new IllegalArgumentException("value must be 0-7");
        writeSystem(SYS_MB_WDG, value);
    }

    /**
     * Write LOCK_CCFILE (session required): RF write protection of blocks 0 and 1.
     *
     * @param lock true locks both blocks
     * @throws IOException on I²C error
     */
    public void lockCcfile(boolean lock) throws IOException { writeSystem(SYS_LOCK_CCFILE, lock ? 0x03 : 0x00); }

    private byte[] capabilityContainer() {
        if (size <= 2048) {
            return new byte[]{(byte) 0xE1, 0x40, (byte) Math.min(size.intdiv(8) as int, 0xFF), 0x01};
        }
        int mlen = size.intdiv(8) as int - 1;
        return new byte[]{(byte) 0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, (byte) (mlen >> 8), (byte) mlen};
    }

    /**
     * Write a Type 5 capability container and one NDEF TLV (03 len … FE; 03 FF hi lo … for ≥ 255 bytes).
     *
     * @param message encoded NDEF message
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the message does not fit
     */
    public void writeNdef(byte[] message) throws IOException {
        ByteArrayOutputStream img = new ByteArrayOutputStream();
        img.writeBytes(capabilityContainer());
        img.write(0x03);
        if (message.length < 0xFF) {
            img.write(message.length);
        } else {
            img.write(0xFF);
            img.write(message.length >> 8);
            img.write(message.length & 0xFF);
        }
        img.writeBytes(message);
        img.write(0xFE);
        byte[] image = img.toByteArray();
        if (image.length > size) throw new IllegalArgumentException("NDEF message too large");
        write(0, image);
    }

    /**
     * Read the message of the first NDEF TLV.
     *
     * @return the message, or empty when no CC or NDEF TLV is found
     * @throws IOException on I²C error
     */
    public byte[] readNdef() throws IOException {
        byte[] cc = read(0, 4);
        int pos;
        if ((cc[0] & 0xFF) == 0xE1) pos = 4;
        else if ((cc[0] & 0xFF) == 0xE2) pos = 8;
        else return new byte[0];
        while (pos < size) {
            int t = read(pos, 1)[0] & 0xFF;
            if (t == 0x00) { pos++; continue; }
            if (t == 0xFE || pos + 2 > size) return new byte[0];
            int length = read(pos + 1, 1)[0] & 0xFF;
            pos += 2;
            if (length == 0xFF) {
                if (pos + 2 > size) return new byte[0];
                byte[] lb = read(pos, 2);
                length = ((lb[0] & 0xFF) << 8) | (lb[1] & 0xFF);
                pos += 2;
            }
            if (t == 0x03) {
                if (pos + length > size) return new byte[0];
                return read(pos, length);
            }
            pos += length;
        }
        return new byte[0];
    }

    /**
     * ACK poll on 0x53 until the write cycle has finished.
     *
     * @throws IOException on timeout
     */
    public void waitWriteComplete() throws IOException { commit(user); }

    private static int sourceToGpo(int source) {
        int gpo = source & 0x07;
        if ((source & (SOURCE_FIELD_RISING | SOURCE_FIELD_FALLING)) != 0) gpo |= GPO_FIELD_CHANGE;
        return gpo | ((source >> 1) & 0x70);
    }

    /**
     * Enable SOURCE_* sources in the static GPO register plus GPO_EN (session required).
     * SOURCE_FIELD_RISING and SOURCE_FIELD_FALLING share FIELD_CHANGE_EN.
     *
     * @param source SOURCE_* mask
     * @throws IOException on I²C error
     */
    public void enableInterrupt(int source) throws IOException {
        writeSystem(SYS_GPO, getGpo() | sourceToGpo(source) | GPO_EN);
        setGpoRuntime(true);
    }

    /**
     * Clear SOURCE_* sources in the static GPO register (session required).
     *
     * @param source SOURCE_* mask
     * @throws IOException on I²C error
     */
    public void disableInterrupt(int source) throws IOException {
        writeSystem(SYS_GPO, getGpo() & ~sourceToGpo(source) & 0xFF);
    }

    /**
     * Read IT_STS_Dyn (cleared on read).
     *
     * @return SOURCE_* mask, 0 = none
     * @throws IOException on I²C error
     */
    public int pollInterrupt() throws IOException { return dynByte(DYN_IT_STS); }

    /**
     * Subscribes to GPO events via the user connection's INT pin, or a 10 ms polling thread.
     *
     * @param callback receives the SOURCE_* mask
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, user.intPin());
    }

    /**
     * Subscribes to GPO events: falling edge (-IE) or rising edge (-JF) on {@code intPin}.
     *
     * @param callback receives the SOURCE_* mask
     * @param intPin   pin wired to GPO, or {@code null} for the 10 ms polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, gpoActiveHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "st25dv-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribes and stops delivery. */
    public void offInterrupt() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException e) {
            // a failed poll is retried on the next edge / tick
        }
    }
}
