package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

/**
 * MCP4661 full driver: {@link MCP4XXXFull} with the part preset. Only the part is fixed;
 * behaviour is identical to the family driver.
 */
public class MCP4661Full extends MCP4XXXFull {

    /**
     * Construct with a high-voltage command pin; performs no I²C traffic.
     *
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting {@code hvc} and the first SCL edge
     */
    public MCP4661Full(RegisterConnection connection, double rAb, double rWiper, OutputPin hvc, int hvcSettleMs) {
        super(connection, "MCP4661", rAb, rWiper, hvc, hvcSettleMs);
    }

    /**
     * Construct without a high-voltage command pin.
     *
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    public MCP4661Full(RegisterConnection connection, double rAb, double rWiper) {
        this(connection, rAb, rWiper, null, 1);
    }

    /**
     * Construct with RAB = 10 kΩ and RW = 75 Ω.
     *
     * @param connection I²C connection bound to an address in the part's range
     */
    public MCP4661Full(RegisterConnection connection) {
        this(connection, 10000.0, 75.0);
    }
}
