package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

/**
 * MCP4551 minimal driver: {@link MCP4XXXMinimal} with the part preset. Only the part is fixed;
 * behaviour is identical to the family driver.
 */
public class MCP4551Minimal extends MCP4XXXMinimal {

    /**
     * Construct with explicit resistances; performs no I²C traffic.
     *
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    public MCP4551Minimal(RegisterConnection connection, double rAb, double rWiper) {
        super(connection, "MCP4551", rAb, rWiper);
    }

    /**
     * Construct with RAB = 10 kΩ and RW = 75 Ω.
     *
     * @param connection I²C connection bound to an address in the part's range
     */
    public MCP4551Minimal(RegisterConnection connection) {
        this(connection, 10000.0, 75.0);
    }
}
