package it.uhde.periph.chips.rfid

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.List
/**
 * PN532 NFC/RFID controller — full interface: target listing at every baud rate, data
 * exchange, MIFARE Classic and NTAG helpers, RF and retry configuration, register access,
 * power-down, the self test, raw commands and the IRQ interrupt API.
 */
@CompileStatic
public class Pn532Full extends Pn532Minimal {

    /** InListPassiveTarget baud rate: 106 kbps ISO/IEC 14443 A. */
    public static final int BAUD_106_A = 0x00
    /** InListPassiveTarget baud rate: 212 kbps FeliCa. */
    public static final int BAUD_212_F = 0x01
    /** InListPassiveTarget baud rate: 424 kbps FeliCa. */
    public static final int BAUD_424_F = 0x02
    /** InListPassiveTarget baud rate: 106 kbps ISO/IEC 14443 B. */
    public static final int BAUD_106_B = 0x03
    private static final byte[] PATTERN = "PERIPH01".getBytes(StandardCharsets.US_ASCII)
    /**
     * GetFirmwareVersion result.
     *
     * @param ic        IC code, 0x32 for the PN532
     * @param version   firmware version
     * @param revision  firmware revision
     * @param support   raw support byte
     * @param iso14443a support bit 0
     * @param iso14443b support bit 1
     * @param iso18092  support bit 2
     */
    static record FirmwareVersion(int ic, int version, int revision, int support,
                                  boolean iso14443a, boolean iso14443b, boolean iso18092) {}

    /**
     * Decoded GetGeneralStatus (per-target entries are skipped).
     *
     * @param error     last error code
     * @param field     external RF field present
     * @param targets   number of active targets
     * @param samStatus SAM status byte
     */
    static record GeneralStatus(int error, boolean field, int targets, int samStatus) {}

    /**
     * A target found by InListPassiveTarget.
     *
     * @param tg      logical target number
     * @param sensRes SENS_RES (ATQA), first byte high; 0 for FeliCa / type B
     * @param selRes  SEL_RES (SAK); 0 for FeliCa / type B
     * @param uid     NFCID1 (type A), NFCID2 (FeliCa) or PUPI (type B)
     * @param ats     ATS (type A), PAD + system code (FeliCa) or ATTRIB_RES (type B)
     */
    static record Target(int tg, int sensRes, int selRes, byte[] uid, byte[] ats) {}

    private volatile Runnable callback
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = () -> {
        Runnable cb = callback
        if (cb != null) cb.run()
    }
    /**
     * Creates the driver; no bus access.
     *
     * @param connection plain I²C connection bound to address 0x24
     */
    public Pn532Full(Connection connection) {
        super(connection)
    }

    /**
     * GetFirmwareVersion.
     *
     * @return IC, version, revision, support byte and its decoded bits
     * @throws IOException on a bus or protocol error
     */
    public FirmwareVersion firmwareVersion() throws IOException {
        byte[] d = command(CMD_GET_FIRMWARE_VERSION, new byte[0], 1.0d)
        if (d.length < 4) throw new Pn532Exception("PN532 short response", -1)
        int s = d[3] & 0xFF
        return new FirmwareVersion(d[0] & 0xFF, d[1] & 0xFF, d[2] & 0xFF, s,
                (s & 0x01) != 0, (s & 0x02) != 0, (s & 0x04) != 0)
    }

    /**
     * GetGeneralStatus.
     *
     * @return error, field present, number of targets, SAM status
     * @throws IOException on a bus or protocol error
     */
    public GeneralStatus generalStatus() throws IOException {
        byte[] d = command(CMD_GET_GENERAL_STATUS, new byte[0], 1.0d)
        if (d.length < 3) throw new Pn532Exception("PN532 short response", -1)
        int n = d[2] & 0xFF
        int sam = 3 + 4 * n
        return new GeneralStatus(d[0] & 0xFF, d[1] != 0, n, sam < d.length ? d[sam] & 0xFF : 0)
    }

    /**
     * InListPassiveTarget with the spec defaults (106 kbps type A, one target, 1 s).
     *
     * @return targets found; empty when none answered
     * @throws IOException on a bus or protocol error
     */
    public List<Target> listPassiveTargets() throws IOException {
        return listPassiveTargets(BAUD_106_A, 1, new byte[0], 1.0d)
    }

    /**
     * InListPassiveTarget.
     *
     * @param baud          BAUD_106_A, BAUD_212_F, BAUD_424_F or BAUD_106_B
     * @param maxTargets    1 or 2
     * @param initiatorData optional InitiatorData (UID, FeliCa polling payload, AFI)
     * @param timeout       response timeout in s; a timeout aborts the command and returns an empty list
     * @return targets found; empty when none answered
     * @throws IOException on a bus or protocol error
     */
    public List<Target> listPassiveTargets(int baud, int maxTargets, byte[] initiatorData, double timeout) throws IOException {
        if (maxTargets < 1 || maxTargets > 2) throw new IllegalArgumentException("maxTargets must be 1 or 2")
        if (baud < 0 || baud > 4) throw new IllegalArgumentException("baud must be 0..4")
        byte[] d = list(maxTargets, baud, initiatorData, timeout)
        List<Target> targets = new ArrayList<>()
        if (d == null || d.length == 0) return targets
        int n = d.length
        int i = 1
        for (int k = 0; k < (d[0] & 0xFF) && i < n; k++) {
            int tg = d[i] & 0xFF
            if (baud == BAUD_106_A) {
                int sens = ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF)
                int sel = d[i + 3] & 0xFF
                int len = d[i + 4] & 0xFF
                byte[] uid = Arrays.copyOfRange(d, i + 5, i + 5 + len)
                i += 5 + len
                byte[] ats = new byte[0]
                if ((sel & 0x20) != 0 && i < n) {
                    int tl = d[i] & 0xFF
                    ats = Arrays.copyOfRange(d, i, Math.min(i + tl, n))
                    i += tl
                }
                targets.add(new Target(tg, sens, sel, uid, ats))
            } else if (baud == BAUD_212_F || baud == BAUD_424_F) {
                int len = d[i + 1] & 0xFF
                targets.add(new Target(tg, 0, 0, Arrays.copyOfRange(d, i + 3, i + 11),
                        Arrays.copyOfRange(d, i + 11, i + 1 + len)))
                i += 1 + len
            } else {
                int len = d[i + 13] & 0xFF
                targets.add(new Target(tg, 0, 0, Arrays.copyOfRange(d, i + 2, i + 6),
                        Arrays.copyOfRange(d, i + 14, i + 14 + len)))
                i += 14 + len
            }
        }
        return targets
    }

    private byte[] statusCommand(int cmd, byte[] data) throws IOException {
        byte[] d = command(cmd, data, 1.0d)
        if (d.length == 0) throw new Pn532Exception("PN532 empty status response", -1)
        int code = d[0] & 0x3F
        if (code != 0) {
            throw new Pn532Exception(String.format("PN532 status error 0x%02X (command 0x%02X)", code, cmd), code)
        }
        return Arrays.copyOfRange(d, 1, d.length)
    }

    /**
     * InDataExchange with an activated target.
     *
     * @param target logical target number
     * @param data   data to send
     * @return data received
     * @throws IOException on a non-zero status ({@link Pn532Exception#code()}) or a bus error
     */
    public byte[] dataExchange(int target, byte[] data) throws IOException {
        byte[] p = new byte[data.length + 1]
        p[0] = (byte) target
        System.arraycopy(data, 0, p, 1, data.length)
        return statusCommand(CMD_IN_DATA_EXCHANGE, p)
    }

    /**
     * InCommunicateThru: raw RF exchange with the current target.
     *
     * @param data raw bytes to transmit
     * @return raw bytes received
     * @throws IOException on a non-zero status or a bus error
     */
    public byte[] communicateThru(byte[] data) throws IOException {
        return statusCommand(CMD_IN_COMMUNICATE_THRU, data)
    }

    /**
     * InRelease.
     *
     * @param target logical target number, 0 = all
     * @throws IOException on a non-zero status or a bus error
     */
    public void release(int target) throws IOException {
        statusCommand(CMD_IN_RELEASE, new byte[]{(byte) target})
    }

    /**
     * InSelect a listed target.
     *
     * @param target logical target number
     * @throws IOException on a non-zero status or a bus error
     */
    public void select(int target) throws IOException {
        statusCommand(CMD_IN_SELECT, new byte[]{(byte) target})
    }

    /**
     * InDeselect (keeps the target's information).
     *
     * @param target logical target number, 0 = all
     * @throws IOException on a non-zero status or a bus error
     */
    public void deselect(int target) throws IOException {
        statusCommand(CMD_IN_DESELECT, new byte[]{(byte) target})
    }

    /**
     * Authenticates a MIFARE Classic sector (0x60 key A / 0x61 key B).
     *
     * @param target logical target number
     * @param block  any block of the sector
     * @param key    6-byte key
     * @param uid    card UID; its last 4 bytes are used
     * @param keyB   use key B
     * @throws IOException on an authentication error (status 0x14) or a bus error
     */
    public void mifareAuthenticate(int target, int block, byte[] key, byte[] uid, boolean keyB) throws IOException {
        if (key.length != 6) throw new IllegalArgumentException("key must be 6 bytes")
        if (uid.length < 4) throw new IllegalArgumentException("uid must have at least 4 bytes")
        byte[] p = new byte[12]
        p[0] = (byte) (keyB ? 0x61 : 0x60)
        p[1] = (byte) block
        System.arraycopy(key, 0, p, 2, 6)
        System.arraycopy(uid, uid.length - 4, p, 8, 4)
        dataExchange(target, p)
    }

    /**
     * Reads a 16-byte MIFARE Classic block (0x30).
     *
     * @param target logical target number
     * @param block  block number
     * @return 16 bytes
     * @throws IOException on a bus or status error
     */
    public byte[] mifareReadBlock(int target, int block) throws IOException {
        byte[] d = dataExchange(target, new byte[]{0x30, (byte) block})
        if (d.length < 16) throw new Pn532Exception("PN532 short block", -1)
        return Arrays.copyOf(d, 16)
    }

    /**
     * Writes a 16-byte MIFARE Classic block (0xA0).
     *
     * @param target logical target number
     * @param block  block number (the sector must be authenticated)
     * @param data   16 bytes
     * @throws IOException on a bus or status error
     */
    public void mifareWriteBlock(int target, int block, byte[] data) throws IOException {
        if (data.length != 16) throw new IllegalArgumentException("data must be 16 bytes")
        byte[] p = new byte[18]
        p[0] = (byte) 0xA0
        p[1] = (byte) block
        System.arraycopy(data, 0, p, 2, 16)
        dataExchange(target, p)
    }

    /**
     * Reads four NTAG / Ultralight pages starting at {@code page} (0x30).
     *
     * @param target logical target number
     * @param page   first page
     * @return 16 bytes
     * @throws IOException on a bus or status error
     */
    public byte[] ntagReadPage(int target, int page) throws IOException {
        return mifareReadBlock(target, page)
    }

    /**
     * Writes one 4-byte NTAG / Ultralight page (0xA2).
     *
     * @param target logical target number
     * @param page   page number
     * @param data   4 bytes
     * @throws IOException on a bus or status error
     */
    public void ntagWritePage(int target, int page, byte[] data) throws IOException {
        if (data.length != 4) throw new IllegalArgumentException("data must be 4 bytes")
        dataExchange(target, new byte[]{(byte) 0xA2, (byte) page, data[0], data[1], data[2], data[3]})
    }

    /**
     * RFConfiguration item 0x01.
     *
     * @param on       RF field on
     * @param autoRfca automatic RF collision avoidance
     * @throws IOException on a bus or protocol error
     */
    public void setRfField(boolean on, boolean autoRfca) throws IOException {
        command(CMD_RF_CONFIGURATION, new byte[]{0x01, (byte) ((on ? 0x02 : 0) | (autoRfca ? 0x01 : 0))}, 1.0d)
    }

    /**
     * RFConfiguration item 0x05 with the defaults atr = 0xFF, psl = 1.
     *
     * @param passiveActivation MxRtyPassiveActivation (0xFF = infinite)
     * @throws IOException on a bus or protocol error
     */
    public void setMaxRetries(int passiveActivation) throws IOException {
        setMaxRetries(passiveActivation, 0xFF, 1)
    }

    /**
     * RFConfiguration item 0x05: retry counts, 0xFF = infinite.
     *
     * @param passiveActivation MxRtyPassiveActivation (default 2)
     * @param atr               MxRtyATR (default 0xFF)
     * @param psl               MxRtyPSL (default 1)
     * @throws IOException on a bus or protocol error
     */
    public void setMaxRetries(int passiveActivation, int atr, int psl) throws IOException {
        command(CMD_RF_CONFIGURATION, new byte[]{0x05, (byte) atr, (byte) psl, (byte) passiveActivation}, 1.0d)
    }

    /**
     * ReadRegister (16-bit address).
     *
     * @param address register address
     * @return register value
     * @throws IOException on a bus or protocol error
     */
    public int readRegister(int address) throws IOException {
        byte[] d = command(CMD_READ_REGISTER, new byte[]{(byte) (address >> 8), (byte) address}, 1.0d)
        if (d.length < 1) throw new Pn532Exception("PN532 short response", -1)
        return d[0] & 0xFF
    }

    /**
     * WriteRegister (16-bit address).
     *
     * @param address register address
     * @param value   byte value
     * @throws IOException on a bus or protocol error
     */
    public void writeRegister(int address, int value) throws IOException {
        command(CMD_WRITE_REGISTER, new byte[]{(byte) (address >> 8), (byte) address, (byte) value}, 1.0d)
    }

    /**
     * PowerDown, waking on I²C (WakeUpEnable 0x20).
     *
     * @throws IOException on a bus or status error
     */
    public void powerDown() throws IOException {
        powerDown(0x20)
    }

    /**
     * PowerDown; the next transaction wakes the chip.
     *
     * @param wakeupSources WakeUpEnable mask (0x20 = I²C)
     * @throws IOException on a bus or status error
     */
    public void powerDown(int wakeupSources) throws IOException {
        statusCommand(CMD_POWER_DOWN, new byte[]{(byte) wakeupSources})
    }

    /**
     * Diagnose communication line test (0x00).
     *
     * @return true when the echo matched
     * @throws IOException on a bus or protocol error
     */
    public boolean selfTest() throws IOException {
        return selfTest(0x00)
    }

    /**
     * Diagnose self test.
     *
     * @param test 0x00 communication line (echo of an 8-byte pattern), 0x01 ROM, 0x02 RAM, ...
     * @return true when the test passed
     * @throws IOException on a bus or protocol error
     */
    public boolean selfTest(int test) throws IOException {
        if (test == 0x00) {
            byte[] req = new byte[9]
            System.arraycopy(PATTERN, 0, req, 1, 8)
            return Arrays.equals(command(CMD_DIAGNOSE, req, 1.0d), req)
        }
        byte[] d = command(CMD_DIAGNOSE, new byte[]{(byte) test}, 1.0d)
        return d.length > 0 && d[0] == 0
    }

    /**
     * Sends a raw command and reads its response (1 s timeout).
     *
     * @param command command code
     * @param data    parameters
     * @return response data after the response code
     * @throws IOException on a bus or protocol error
     */
    public byte[] sendCommand(int command, byte[] data) throws IOException {
        return sendCommand(command, data, 1.0d)
    }

    /**
     * Sends a raw command and reads its response.
     *
     * @param cmd     command code
     * @param data    parameters
     * @param timeout response timeout in s
     * @return response data after the response code
     * @throws IOException on a bus or protocol error
     */
    public byte[] sendCommand(int cmd, byte[] data, double timeout) throws IOException {
        return command(cmd, data, timeout)
    }

    /**
     * Writes a command frame and reads its ACK, without waiting for the response.
     *
     * @param command command code
     * @param data    parameters
     * @throws IOException on a bus error or a missing ACK
     */
    public synchronized void startCommand(int command, byte[] data) throws IOException {
        start(command, data)
    }

    /**
     * Reads the response to a command started with {@link #startCommand}; a timeout aborts it.
     *
     * @param command the started command code
     * @param timeout response timeout in s
     * @return response data after the response code
     * @throws IOException on a bus or protocol error or a timeout
     */
    public synchronized byte[] readResponse(int command, double timeout) throws IOException {
        return response(command, timeout)
    }

    /**
     * Polls the status byte every 1 ms until a frame is ready.
     *
     * @param timeout maximum wait in s
     * @return true when a frame is ready
     * @throws IOException on a bus error
     */
    public synchronized boolean waitReady(double timeout) throws IOException {
        return waitFrame(timeout)
    }

    /**
     * Checks the status byte.
     *
     * @return true when an ACK or response frame is ready
     * @throws IOException on a bus error
     */
    public synchronized boolean pollInterrupt() throws IOException {
        return ready()
    }

    /**
     * Subscribes to frame-ready events on the connection's IRQ pin (or the polling fallback).
     *
     * @param callback called with no arguments; read the frame with {@link #readResponse}
     */
    public void onInterrupt(Runnable callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribes to frame-ready events: falling edge on {@code intPin}, or a 10 ms status-byte
     * poll that fires on each not-ready → ready transition when {@code intPin} is null.
     *
     * @param callback called with no arguments
     * @param intPin   pin wired to IRQ, or {@code null} for the polling fallback
     */
    public void onInterrupt(Runnable callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread(() -> {
                boolean was = false
                while (polling) {
                    boolean now
                    try {
                        now = pollInterrupt()
                    } catch (IOException e) {
                        now = false
                    }
                    if (now && !was) edgeHandler.onEdge()
                    was = now
                    try {
                        Thread.sleep(10)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }, "pn532-poll")
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    /** Unsubscribes and stops delivery. */
    public void offInterrupt() {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }
}
