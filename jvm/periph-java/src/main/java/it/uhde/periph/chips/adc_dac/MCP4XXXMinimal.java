package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.Locale;

/**
 * MCP4XXX family — MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X I²C digital
 * potentiometers (Microchip), minimal driver.
 *
 * <p>One driver for all sixteen parts. A {@link PartInfo} descriptor (looked up by part name) carries
 * the channel count, resolution (7-bit: max code 128, 8-bit: 256), pot / rheostat pinout, memory type
 * and I²C address range; everything else is one code path. A transaction starts with a command byte
 * {@code AD3 AD2 AD1 AD0 C1 C0 D9 D8} that plays the role of the register address of the
 * {@link RegisterConnection}; a read returns two bytes ({@code 0000 000D8}, {@code D7..D0}).
 *
 * <h2>Defaults</h2>
 * <p>Part MCP4532, RAB = 10 kΩ, RW = 75 Ω; the wipers keep their power-up value until written; the
 * constructor performs no I²C traffic ({@link #init()} does the bus check). An invalid channel or
 * unknown part throws {@link IllegalArgumentException}.
 */
public class MCP4XXXMinimal {

    /** One row of the family descriptor table. */
    public static final class PartInfo {
        /** Part name, e.g. {@code "MCP4532"}. */
        public final String name;
        /** Number of resistor networks (1 or 2). */
        public final int channels;
        /** Maximum wiper code: 128 (7-bit) or 256 (8-bit). */
        public final int maxCode;
        /** True for the X1 parts (terminal A on a pin). */
        public final boolean potentiometer;
        /** True for the 454X / 456X / 464X / 466X parts (EEPROM). */
        public final boolean nonvolatile;
        /** True for the MCP4641 / MCP4661 (hardware WP pin). */
        public final boolean wpPin;
        /** Lowest 7-bit I²C address (all address pins low). */
        public final int addrMin;
        /** Highest 7-bit I²C address. */
        public final int addrMax;

        PartInfo(String name, int channels, int maxCode, boolean potentiometer, boolean nonvolatile,
                 boolean wpPin, int addrMin, int addrMax) {
            this.name = name;
            this.channels = channels;
            this.maxCode = maxCode;
            this.potentiometer = potentiometer;
            this.nonvolatile = nonvolatile;
            this.wpPin = wpPin;
            this.addrMin = addrMin;
            this.addrMax = addrMax;
        }
    }

    private static final PartInfo[] PARTS = {
        new PartInfo("MCP4531", 1, 128, true,  false, false, 0x2E, 0x2F),
        new PartInfo("MCP4532", 1, 128, false, false, false, 0x58, 0x5B),
        new PartInfo("MCP4551", 1, 256, true,  false, false, 0x2E, 0x2F),
        new PartInfo("MCP4552", 1, 256, false, false, false, 0x58, 0x5B),
        new PartInfo("MCP4631", 2, 128, true,  false, false, 0x28, 0x2F),
        new PartInfo("MCP4632", 2, 128, false, false, false, 0x58, 0x5B),
        new PartInfo("MCP4651", 2, 256, true,  false, false, 0x28, 0x2F),
        new PartInfo("MCP4652", 2, 256, false, false, false, 0x58, 0x5B),
        new PartInfo("MCP4541", 1, 128, true,  true,  false, 0x2E, 0x2F),
        new PartInfo("MCP4542", 1, 128, false, true,  false, 0x58, 0x5B),
        new PartInfo("MCP4561", 1, 256, true,  true,  false, 0x2E, 0x2F),
        new PartInfo("MCP4562", 1, 256, false, true,  false, 0x58, 0x5B),
        new PartInfo("MCP4641", 2, 128, true,  true,  true,  0x28, 0x2F),
        new PartInfo("MCP4642", 2, 128, false, true,  false, 0x58, 0x5B),
        new PartInfo("MCP4661", 2, 256, true,  true,  true,  0x28, 0x2F),
        new PartInfo("MCP4662", 2, 256, false, true,  false, 0x58, 0x5B),
    };

    /** Memory addresses (command byte bits 7:4). */
    protected static final int ADDR_WIPER0 = 0x00, ADDR_NVWIPER0 = 0x02, ADDR_TCON = 0x04,
            ADDR_STATUS = 0x05, ADDR_DATA0 = 0x06, ADDR_WP = 0x0F;
    /** Operations (command byte bits 3:2). */
    protected static final int OP_WRITE = 0x00, OP_INC = 0x04, OP_DEC = 0x08, OP_READ = 0x0C;
    /** TCON power-up value (all terminals connected, General Call enabled). */
    protected static final int TCON_DEFAULT = 0x1FF;
    /** Per-channel TCON bit positions: HW, A, W, B. */
    protected static final int[][] TCON_BITS = {{3, 2, 1, 0}, {7, 6, 5, 4}};

    protected final RegisterConnection connection;
    protected final PartInfo desc;
    protected double rAb;
    protected double rWiper;

    /**
     * Look a part up by name.
     *
     * @param part part name, e.g. {@code "mcp4532"} (case-insensitive)
     * @return the descriptor
     * @throws IllegalArgumentException if the part is unknown
     */
    public static PartInfo lookup(String part) {
        String key = part.toUpperCase(Locale.ROOT);
        for (PartInfo p : PARTS) {
            if (p.name.equals(key)) return p;
        }
        throw new IllegalArgumentException("MCP4XXX: unknown part " + part);
    }

    /**
     * Default 7-bit I²C address (all address pins low) of a part.
     *
     * @param part part name
     * @return {@code 0x2E} (45X1), {@code 0x58} (45X2 / 46X2) or {@code 0x28} (46X1)
     */
    public static int defaultAddress(String part) {
        return lookup(part).addrMin;
    }

    /**
     * Construct the driver; performs no I²C traffic.
     *
     * @param connection I²C connection bound to an address in the part's range
     * @param part       part name {@code "MCP4531"} … {@code "MCP4662"} (case-insensitive)
     * @param rAb        end-to-end resistance RAB in ohms (5000, 10000, 50000 or 100000)
     * @param rWiper     wiper resistance RW in ohms (typically 75)
     */
    public MCP4XXXMinimal(RegisterConnection connection, String part, double rAb, double rWiper) {
        this.connection = connection;
        this.desc = lookup(part);
        this.rAb = rAb;
        this.rWiper = rWiper;
    }

    /**
     * Construct with RAB = 10 kΩ and RW = 75 Ω.
     *
     * @param connection I²C connection bound to an address in the part's range
     * @param part       part name
     */
    public MCP4XXXMinimal(RegisterConnection connection, String part) {
        this(connection, part, 10000.0, 75.0);
    }

    /**
     * Construct for an MCP4532 with RAB = 10 kΩ and RW = 75 Ω.
     *
     * @param connection I²C connection bound to an address in 0x58–0x5B
     */
    public MCP4XXXMinimal(RegisterConnection connection) {
        this(connection, "MCP4532");
    }

    /**
     * Bus check: reads the wiper register(s), TCON (and STATUS on non-volatile parts); performs no writes.
     *
     * @throws IOException on I²C error
     */
    public void init() throws IOException {
        for (int c = 0; c < desc.channels; c++) readWiper(c);
        read9(ADDR_TCON);
        if (desc.nonvolatile) read9(ADDR_STATUS);
    }

    /** Validate a channel index against the selected part. */
    protected void checkChannel(int channel) {
        if (channel < 0 || channel >= desc.channels) {
            throw new IllegalArgumentException("MCP4XXX: channel out of range for " + desc.name);
        }
    }

    /** Read a 9-bit register: command byte with C = 11, two data bytes. */
    protected int read9(int address) throws IOException {
        byte[] d = connection.read((address << 4) | OP_READ, 2);
        return ((d[0] & 0x01) << 8) | (d[1] & 0xFF);
    }

    /** Write a 9-bit register: D8 travels in the command byte, D7..D0 in the data byte. */
    protected void write9(int address, int value) throws IOException {
        connection.write((address << 4) | OP_WRITE | ((value >> 8) & 0x01), new byte[] {(byte) value});
    }

    /** Read a volatile wiper register, clamped to the part's max code. */
    protected int readWiper(int channel) throws IOException {
        return Math.min(read9(ADDR_WIPER0 + channel), desc.maxCode);
    }

    /** Clamp a code to 0..max code. */
    protected int clamp(long code) {
        return (int) Math.max(0, Math.min(desc.maxCode, code));
    }

    /**
     * Set the volatile wiper of a network; the code is clamped to 0..max code. A network locked by
     * WiperLock ignores the write.
     *
     * @param code    wiper position (0 = W at B, max code 128 / 256 = W at A)
     * @param channel network index, 0 or 1 (dual parts only)
     * @throws IOException on I²C error
     */
    public void setWiper(int code, int channel) throws IOException {
        checkChannel(channel);
        write9(ADDR_WIPER0 + channel, clamp(code));
    }

    /**
     * Set the volatile wiper of network 0.
     *
     * @param code wiper position
     * @throws IOException on I²C error
     */
    public void setWiper(int code) throws IOException {
        setWiper(code, 0);
    }

    /**
     * Read the volatile wiper of a network.
     *
     * @param channel network index
     * @return wiper position 0..max code (values above full scale are clamped)
     * @throws IOException on I²C error
     */
    public int getWiper(int channel) throws IOException {
        checkChannel(channel);
        return readWiper(channel);
    }

    /**
     * Read the volatile wiper of network 0.
     *
     * @return wiper position
     * @throws IOException on I²C error
     */
    public int getWiper() throws IOException {
        return getWiper(0);
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     *
     * @param ohms    target wiper-to-B resistance in ohms (clamped to the reachable range)
     * @param channel network index
     * @throws IOException on I²C error
     */
    public void setResistance(double ohms, int channel) throws IOException {
        checkChannel(channel);
        setWiper((int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2,
                Math.round((ohms - rWiper) / rAb * desc.maxCode))), channel);
    }

    /**
     * Set the W–B resistance of network 0.
     *
     * @param ohms target resistance in ohms
     * @throws IOException on I²C error
     */
    public void setResistance(double ohms) throws IOException {
        setResistance(ohms, 0);
    }

    /**
     * Read the W–B resistance for the current wiper position.
     *
     * @param channel network index
     * @return resistance in ohms, N / maxCode × RAB + RW
     * @throws IOException on I²C error
     */
    public double getResistance(int channel) throws IOException {
        return getWiper(channel) / (double) desc.maxCode * rAb + rWiper;
    }

    /**
     * Read the W–B resistance of network 0.
     *
     * @return resistance in ohms
     * @throws IOException on I²C error
     */
    public double getResistance() throws IOException {
        return getResistance(0);
    }
}
