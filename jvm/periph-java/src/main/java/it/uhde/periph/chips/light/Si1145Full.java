package it.uhde.periph.chips.light;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * Si1145 full interface — extends {@link Si1145Minimal} with per-channel reads, the channel list,
 * LED and photodiode selection, gains, ranges, alignment, UV coefficients, calibration,
 * autonomous mode, the I²C address, reset and the INT interrupt (active low, driven after
 * {@link #enableInterrupt(int)}, released when the IRQ_STATUS bits are written back).
 */
public class Si1145Full extends Si1145Minimal {

    /** AUX_ADCMUX: die temperature. */
    public static final int AUX_TEMPERATURE = 0x65;
    /** AUX_ADCMUX: supply voltage. */
    public static final int AUX_VDD = 0x75;

    /**
     * Identification registers.
     *
     * @param partId PART_ID (0x45 Si1145, 0x46 Si1146, 0x47 Si1147)
     * @param revId  REV_ID (hardware revision)
     * @param seqId  SEQ_ID (sequencer revision; 0x01 swaps the MEAS_RATE bytes)
     */
    public record Id(int partId, int revId, int seqId) {}

    /**
     * Raw data registers 0x22–0x2D.
     *
     * @param visible  ALS_VIS_DATA
     * @param infrared ALS_IR_DATA
     * @param ps1      PS1_DATA
     * @param ps2      PS2_DATA (Si1146/47)
     * @param ps3      PS3_DATA (Si1147)
     * @param aux      AUX_DATA (UV index × 100 while UV is enabled)
     */
    public record Raw(int visible, int infrared, int ps1, int ps2, int ps3, int aux) {}

    /**
     * Decoded RESPONSE.
     *
     * @param counter 4-bit command counter (0 on error)
     * @param error   0 = none, 0x80 invalid setting, 0x88–0x8E ADC overflow
     */
    public record Response(int counter, int error) {}

    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Creates the driver; no bus access.
     *
     * @param connection register-addressed I²C connection bound to 0x60
     */
    public Si1145Full(RegisterConnection connection) {
        super(connection);
    }

    private static void check(String name, long value, long lo, long hi) {
        if (value < lo || value > hi) throw new IllegalArgumentException(name + " must be " + lo + ".." + hi);
    }

    private int ledCount() {
        return partId - 0x44;
    }

    /**
     * Reads PART_ID, REV_ID and SEQ_ID.
     *
     * @return the identification registers
     * @throws IOException on a bus error
     */
    public Id readId() throws IOException {
        byte[] b = connection.read(REG_PART_ID, 3);
        return new Id(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
    }

    /**
     * Forced PS measurement (PS_FORCE) of PS1.
     *
     * @return raw PS1 count
     * @throws IOException on failure
     */
    public int readProximity() throws IOException {
        return readProximity(1);
    }

    /**
     * Forced PS measurement (PS_FORCE) and one proximity channel.
     *
     * @param channel PS channel 1–3 (Si1145: 1); must be enabled in CHLIST
     * @return raw proximity count (rises as an object approaches)
     * @throws IOException on failure
     * @throws IllegalArgumentException if the channel is not supported or not enabled
     */
    public int readProximity(int channel) throws IOException {
        check("channel", channel, 1, ledCount());
        if ((chList & (1 << (channel - 1))) == 0) throw new IllegalArgumentException("PS" + channel + " is not enabled (setChannels)");
        measure(true, false);
        return readData()[1 + channel];
    }

    private int alsValue(int ch, int index, String name) throws IOException {
        if ((chList & ch) == 0) throw new IllegalArgumentException(name + " is not enabled (setChannels)");
        measure(false, true);
        return readData()[index];
    }

    /**
     * Forced ALS measurement and the UV index.
     *
     * @return AUX_DATA / 100
     * @throws IOException on failure
     * @throws IllegalArgumentException if UV is not enabled
     */
    public double readUvIndex() throws IOException {
        return alsValue(CH_UV, 5, "UV") / 100.0;
    }

    /**
     * Forced ALS measurement and the raw visible count.
     *
     * @return ALS_VIS_DATA
     * @throws IOException on failure
     * @throws IllegalArgumentException if the visible channel is not enabled
     */
    public int readVisible() throws IOException {
        return alsValue(CH_ALS_VIS, 0, "visible");
    }

    /**
     * Forced ALS measurement and the raw infrared count.
     *
     * @return ALS_IR_DATA
     * @throws IOException on failure
     * @throws IllegalArgumentException if the infrared channel is not enabled
     */
    public int readInfrared() throws IOException {
        return alsValue(CH_ALS_IR, 1, "infrared");
    }

    /**
     * Reads the latest data registers without measuring.
     *
     * @return raw 16-bit values
     * @throws IOException on a bus error
     */
    public Raw readRaw() throws IOException {
        int[] v = readData();
        return new Raw(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    private int readAux(int mux) throws IOException {
        int saved = chList;
        paramSet(PARAM_AUX_ADCMUX, mux);
        paramSet(PARAM_CHLIST, CH_AUX);
        chList = CH_AUX;
        try {
            measure(false, true);
            return readData()[5];
        } finally {
            paramSet(PARAM_CHLIST, saved);
            chList = saved;
        }
    }

    /**
     * Die temperature on the auxiliary channel (AUX_ADCMUX 0x65); CHLIST is switched to EN_AUX
     * temporarily and restored.
     *
     * @return raw relative temperature count
     * @throws IOException on failure
     */
    public int readTemperatureRaw() throws IOException {
        return readAux(AUX_TEMPERATURE);
    }

    /**
     * Supply voltage on the auxiliary channel (AUX_ADCMUX 0x75); CHLIST restored afterwards.
     *
     * @return raw VDD count
     * @throws IOException on failure
     */
    public int readVddRaw() throws IOException {
        return readAux(AUX_VDD);
    }

    /**
     * Selects UV, visible, infrared and PS1 (CHLIST); PS2, PS3 and AUX off.
     *
     * @param uv       UV index (in AUX_DATA)
     * @param visible  visible ambient light
     * @param infrared infrared ambient light
     * @param ps1      proximity channel 1
     * @throws IOException on failure
     */
    public void setChannels(boolean uv, boolean visible, boolean infrared, boolean ps1) throws IOException {
        setChannels(uv, visible, infrared, ps1, false, false, false);
    }

    /**
     * Selects the measured channels (CHLIST).
     *
     * @param uv       UV index (in AUX_DATA); excludes {@code aux}
     * @param visible  visible ambient light
     * @param infrared infrared ambient light
     * @param ps1      proximity channel 1
     * @param ps2      proximity channel 2 (Si1146/47)
     * @param ps3      proximity channel 3 (Si1147)
     * @param aux      auxiliary channel (AUX_ADCMUX)
     * @throws IOException on failure
     * @throws IllegalArgumentException for uv together with aux or an unsupported PS channel
     */
    public void setChannels(boolean uv, boolean visible, boolean infrared, boolean ps1, boolean ps2,
                            boolean ps3, boolean aux) throws IOException {
        if (uv && aux) throw new IllegalArgumentException("uv and aux share AUX_DATA; enable only one");
        if ((ps2 && ledCount() < 2) || (ps3 && ledCount() < 3)) throw new IllegalArgumentException("PS channel not available on this part");
        int mask = (uv ? CH_UV : 0) | (aux ? CH_AUX : 0) | (infrared ? CH_ALS_IR : 0) | (visible ? CH_ALS_VIS : 0)
                | (ps3 ? CH_PS3 : 0) | (ps2 ? CH_PS2 : 0) | (ps1 ? CH_PS1 : 0);
        paramSet(PARAM_CHLIST, mask);
        chList = mask;
    }

    /**
     * Sets an LED drive current (PS_LED21 / PS_LED3).
     *
     * @param led   LED 1–3 (Si1145: 1 only)
     * @param level current level 0 (off) … 15 (maximum, ~359 mA)
     * @throws IOException on a bus error
     */
    public void setLedCurrent(int led, int level) throws IOException {
        check("led", led, 1, ledCount());
        check("level", level, 0, 15);
        if (led == 3) {
            writeByte(REG_PS_LED3, level);
            return;
        }
        int cur = readByte(REG_PS_LED21);
        writeByte(REG_PS_LED21, led == 1 ? (cur & 0xF0) | level : (cur & 0x0F) | (level << 4));
    }

    /**
     * Selects the LEDs a PS channel drives (PSLED12_SELECT / PSLED3_SELECT).
     *
     * @param channel PS channel 1–3
     * @param leds    LED mask (bit 0 LED1, bit 1 LED2, bit 2 LED3), only the part's LEDs
     * @throws IOException on failure
     */
    public void setPsLed(int channel, int leds) throws IOException {
        check("channel", channel, 1, 3);
        check("leds", leds, 0, (1 << ledCount()) - 1);
        if (channel == 3) {
            paramSet(PARAM_PSLED3_SELECT, leds);
            return;
        }
        int cur = paramQuery(PARAM_PSLED12_SELECT);
        paramSet(PARAM_PSLED12_SELECT, channel == 1 ? (cur & 0x70) | leds : (cur & 0x07) | (leds << 4));
    }

    /**
     * Selects the photodiode of a PS channel (PSn_ADCMUX).
     *
     * @param channel PS channel 1–3
     * @param large   large IR photodiode (0x03) or small (0x00)
     * @throws IOException on failure
     */
    public void setPsPhotodiode(int channel, boolean large) throws IOException {
        check("channel", channel, 1, 3);
        paramSet(PARAM_PS1_ADCMUX + channel - 1, large ? 0x03 : 0x00);
    }

    /**
     * Sets the ADC gains (integration time × 2^n) and the recommended recovery counts (one's
     * complement of the gain). Do not combine a high PS gain with a high LED current.
     *
     * @param visible   ALS_VIS_ADC_GAIN 0–7
     * @param infrared  ALS_IR_ADC_GAIN 0–7
     * @param proximity PS_ADC_GAIN 0–7
     * @throws IOException on failure
     */
    public void setGain(int visible, int infrared, int proximity) throws IOException {
        check("visible", visible, 0, 7);
        check("infrared", infrared, 0, 7);
        check("proximity", proximity, 0, 7);
        int[][] sets = {
            {visible, PARAM_ALS_VIS_ADC_GAIN, PARAM_ALS_VIS_ADC_COUNTER},
            {infrared, PARAM_ALS_IR_ADC_GAIN, PARAM_ALS_IR_ADC_COUNTER},
            {proximity, PARAM_PS_ADC_GAIN, PARAM_PS_ADC_COUNTER},
        };
        for (int[] s : sets) {
            paramSet(s[1], s[0]);
            paramSet(s[2], ((~s[0]) & 0x07) << 4);
        }
    }

    /**
     * Selects the high signal range (sensitivity ÷ 14.5) per measurement.
     *
     * @param visible   VIS_RANGE
     * @param infrared  IR_RANGE
     * @param proximity PS_RANGE (PS_ADC_MODE stays 1)
     * @throws IOException on failure
     */
    public void setHighRange(boolean visible, boolean infrared, boolean proximity) throws IOException {
        paramSet(PARAM_ALS_VIS_ADC_MISC, visible ? 0x20 : 0);
        paramSet(PARAM_ALS_IR_ADC_MISC, infrared ? 0x20 : 0);
        paramSet(PARAM_PS_ADC_MISC, proximity ? 0x24 : 0x04);
    }

    /**
     * Reports the 16 LSBs instead of the 16 MSBs of the ADC result.
     *
     * @param visible  ALS_VIS_ALIGN
     * @param infrared ALS_IR_ALIGN
     * @param ps1      PS1_ALIGN
     * @param ps2      PS2_ALIGN
     * @param ps3      PS3_ALIGN
     * @throws IOException on failure
     */
    public void setAlignment(boolean visible, boolean infrared, boolean ps1, boolean ps2, boolean ps3) throws IOException {
        paramSet(PARAM_ALS_ENCODING, (infrared ? 0x20 : 0) | (visible ? 0x10 : 0));
        paramSet(PARAM_PS_ENCODING, (ps3 ? 0x40 : 0) | (ps2 ? 0x20 : 0) | (ps1 ? 0x10 : 0));
    }

    /**
     * Writes UCOEF0..3.
     *
     * @param ucoef four bytes; {@link #UCOEF_DEFAULT} gives the UV index × 100
     * @throws IOException on a bus error
     */
    public void setUvCoefficients(int[] ucoef) throws IOException {
        if (ucoef.length != 4) throw new IllegalArgumentException("ucoef must have four entries");
        for (int c : ucoef) check("ucoef", c, 0, 0xFF);
        connection.write(REG_UCOEF0, toBytes(ucoef));
    }

    /**
     * Factory calibration (GET_CAL); overwrites the data registers.
     *
     * @return 12 calibration bytes
     * @throws IOException on failure
     */
    public int[] readCalibration() throws IOException {
        command(CMD_GET_CAL);
        byte[] b = connection.read(REG_DATA, 12);
        int[] cal = new int[12];
        for (int i = 0; i < 12; i++) cal[i] = b[i] & 0xFF;
        return cal;
    }

    /**
     * Starts autonomous PS + ALS measurements.
     *
     * @param intervalS interval in seconds (31.25 µs … 2.048 s)
     * @throws IOException on failure
     */
    public void startAutonomous(double intervalS) throws IOException {
        startAutonomous(intervalS, true, true);
    }

    /**
     * Starts autonomous measurements: MEAS_RATE = round(interval / 31.25 µs) (bytes swapped when
     * SEQ_ID = 0x01), then PS_AUTO / ALS_AUTO / PSALS_AUTO.
     *
     * @param intervalS interval in seconds (31.25 µs … 2.048 s)
     * @param proximity run the PS channels
     * @param als       run the ALS/UV channels
     * @throws IOException on failure
     */
    public void startAutonomous(double intervalS, boolean proximity, boolean als) throws IOException {
        long rate = Math.round(intervalS / 31.25e-6);
        check("rate ticks", rate, 1, 0xFFFF);
        if (!(proximity || als)) throw new IllegalArgumentException("select proximity and/or als");
        if (seqId == 0x01) {
            writeByte(REG_PS_RATE, (int) (rate & 0xFF));
            writeByte(REG_MEAS_RATE0, (int) (rate >> 8));
        } else {
            connection.write(REG_MEAS_RATE0, new byte[]{(byte) rate, (byte) (rate >> 8)});
        }
        command(proximity && als ? CMD_PSALS_AUTO : proximity ? CMD_PS_AUTO : CMD_ALS_AUTO);
    }

    /**
     * Pauses the autonomous loop (PSALS_PAUSE).
     *
     * @throws IOException on failure
     */
    public void pause() throws IOException {
        command(CMD_PSALS_PAUSE);
    }

    /**
     * Starts a forced PS + ALS measurement without waiting (PSALS_FORCE).
     *
     * @throws IOException on failure
     */
    public void force() throws IOException {
        force(true, true);
    }

    /**
     * Starts a forced measurement without waiting (PS/ALS/PSALS_FORCE).
     *
     * @param proximity measure the PS channels
     * @param als       measure the ALS/UV channels
     * @throws IOException on failure
     */
    public void force(boolean proximity, boolean als) throws IOException {
        if (!(proximity || als)) throw new IllegalArgumentException("select proximity and/or als");
        command(forceCommand(proximity, als));
    }

    /**
     * Decodes RESPONSE.
     *
     * @return counter and error code
     * @throws IOException on a bus error
     */
    public Response readResponse() throws IOException {
        int r = readByte(REG_RESPONSE);
        return (r & 0x80) != 0 ? new Response(0, r) : new Response(r & 0x0F, 0);
    }

    /**
     * Changes the I²C address (I2C_ADDR, then BUSADDR; volatile). The chip answers on the new
     * address at once; open a new connection afterwards.
     *
     * @param address new 7-bit address 0x08–0x77
     * @throws IOException on failure
     */
    public void setI2cAddress(int address) throws IOException {
        check("address", address, 0x08, 0x77);
        paramSet(PARAM_I2C_ADDR, address);
        writeByte(REG_COMMAND, CMD_BUSADDR);
    }

    /**
     * Software reset, 10 ms wait, HW_KEY and the Minimal defaults again.
     *
     * @throws IOException on failure
     */
    public void reset() throws IOException {
        writeByte(REG_COMMAND, CMD_RESET);
        sleepMs(10);
        applyDefaults();
    }

    /**
     * Drives INT for sources (INT_CFG.INT_OE and IRQ_ENABLE).
     *
     * @param source SOURCE_* mask
     * @throws IOException on a bus error
     */
    public void enableInterrupt(int source) throws IOException {
        irqEnable = (irqEnable | source) & SOURCE_MASK;
        writeByte(REG_INT_CFG, 0x01);
        writeByte(REG_IRQ_ENABLE, irqEnable);
    }

    /**
     * Clears sources from IRQ_ENABLE. Forced reads only wait for enabled sources (none: a fixed
     * 100 ms wait).
     *
     * @param source SOURCE_* mask
     * @throws IOException on a bus error
     */
    public void disableInterrupt(int source) throws IOException {
        irqEnable &= ~source & SOURCE_MASK;
        writeByte(REG_IRQ_ENABLE, irqEnable);
    }

    /**
     * Reads IRQ_STATUS and writes it back to clear it (releases INT).
     *
     * @return pending SOURCE_* mask among the enabled sources, 0 = none
     * @throws IOException on a bus error
     */
    public int pollInterrupt() throws IOException {
        return clearIrq() & irqEnable;
    }

    /**
     * Subscribes to measurement events on the connection's INT pin (falling edge), or a 10 ms
     * polling thread when no pin is wired.
     *
     * @param callback receives the SOURCE_* mask
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribes to measurement events (falling edge on {@code intPin}).
     *
     * @param callback receives the SOURCE_* mask
     * @param intPin   pin wired to INT, or {@code null} for the polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
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
            }, "si1145-poll");
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
