package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * MCP4725 — full driver. Extends {@link Mcp4725Minimal} with EEPROM persistence,
 * power-down control, read-back, and General Call commands (reset / wake-up).
 *
 * <p>General Call commands (reset, wakeUp) require a second transport bound to
 * address 0x00. Pass {@code null} to disable those methods.
 *
 * <h2>EEPROM write timing</h2>
 * The EEPROM write takes up to 50 ms. New write commands are silently ignored
 * while RDY/BSY is low. Poll {@link #isEepromReady()} or wait 50 ms before
 * issuing a second EEPROM write.
 */
public class Mcp4725Full extends Mcp4725Minimal {

    /**
     * Read-back state of the MCP4725.
     *
     * @param code            current DAC register value (0–4095)
     * @param voltageFraction DAC register value as fraction of V_DD (code / 4095.0)
     * @param powerDown       current power-down mode of the DAC register (0–3)
     * @param eepromCode      EEPROM-stored DAC value (0–4095)
     * @param eepromPowerDown EEPROM-stored power-down mode (0–3)
     * @param eepromReady     true when EEPROM write is complete (RDY/BSY = 1)
     */
    public record ReadResult(
            int code,
            double voltageFraction,
            int powerDown,
            int eepromCode,
            int eepromPowerDown,
            boolean eepromReady
    ) {}

    private final Transport generalCall;

    /**
     * Construct the full driver.
     *
     * @param transport   I²C transport bound to the MCP4725 device address
     * @param generalCall I²C transport bound to address 0x00 for General Call
     *                    commands, or {@code null} to disable reset/wakeUp
     */
    public Mcp4725Full(Transport transport, Transport generalCall) {
        super(transport);
        this.generalCall = generalCall;
    }

    /**
     * Write fraction to both the DAC register and EEPROM.
     *
     * @param fraction target output level, 0.0–1.0
     * @throws IOException on I²C error
     */
    public void setVoltageEeprom(double fraction) throws IOException {
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        setRawEeprom((int) Math.round(fraction * 4095));
    }

    /**
     * Write a raw 12-bit code to both the DAC register and EEPROM.
     *
     * <p>The value persists across power cycles. Wait up to 50 ms before
     * issuing another EEPROM write.
     *
     * @param code 12-bit DAC value (0–4095)
     * @throws IOException on I²C error
     */
    public void setRawEeprom(int code) throws IOException {
        code = Math.max(0, Math.min(4095, code));
        lastCode = code;
        // Write DAC + EEPROM command: C2=0 C1=1 C0=1 → 0b011x_xPD1_PD0_x
        // Byte 1: [0 1 1 X X PD1 PD0 X] = 0x60 (normal mode)
        // Byte 2: D11–D4
        // Byte 3: D3–D0 in bits 7:4, bits 3:0 don't care
        transport.write(new byte[]{
                0x60,
                (byte) ((code >> 4) & 0xFF),
                (byte) ((code << 4) & 0xF0)
        });
    }

    /**
     * Read the current state of the device (5-byte read response).
     *
     * @return snapshot of DAC register, EEPROM, power-down, and ready flag
     * @throws IOException on I²C error
     */
    public ReadResult read() throws IOException {
        byte[] b = transport.read(5);
        boolean eepromReady = (b[0] & 0x80) != 0;
        int powerDown      = (b[0] >> 2) & 0x03;
        int code           = ((b[1] & 0xFF) << 4) | ((b[2] & 0xFF) >> 4);
        int eepromPD       = (b[3] >> 5) & 0x03;
        int eepromCode     = ((b[3] & 0x0F) << 8) | (b[4] & 0xFF);
        return new ReadResult(code, code / 4095.0, powerDown, eepromCode, eepromPD, eepromReady);
    }

    /**
     * Enter or exit power-down mode using a Fast Write.
     *
     * <p>The last-written DAC code is preserved in the output register so the
     * output resumes at the same level when mode 0 is selected again.
     *
     * @param mode 0 = normal, 1 = 1 kΩ to GND, 2 = 100 kΩ to GND, 3 = 500 kΩ to GND
     * @throws IOException on I²C error
     */
    public void setPowerDown(int mode) throws IOException {
        mode = Math.max(0, Math.min(3, mode));
        // Fast Write with PD bits set: [0 0 PD1 PD0 D11-D8] [D7-D0]
        transport.write(new byte[]{
                (byte) (((mode & 0x03) << 4) | ((lastCode >> 8) & 0x0F)),
                (byte) (lastCode & 0xFF)
        });
    }

    /**
     * Send General Call Wake-Up (address 0x00, command 0x09).
     *
     * <p>Clears the power-down bits in the DAC register of all MCP47xx devices
     * on the bus that support General Call.
     *
     * @throws IOException              on I²C error
     * @throws IllegalStateException    if no General Call transport was provided
     */
    public void wakeUp() throws IOException {
        requireGeneralCall();
        generalCall.write(new byte[]{0x09});
    }

    /**
     * Send General Call Reset (address 0x00, command 0x06).
     *
     * <p>Triggers an internal power-on reset on all MCP47xx devices on the bus
     * that support General Call, reloading EEPROM contents into the DAC register.
     *
     * @throws IOException              on I²C error
     * @throws IllegalStateException    if no General Call transport was provided
     */
    public void reset() throws IOException {
        requireGeneralCall();
        generalCall.write(new byte[]{0x06});
    }

    /**
     * Read the RDY/BSY bit.
     *
     * @return true when the EEPROM write is complete
     * @throws IOException on I²C error
     */
    public boolean isEepromReady() throws IOException {
        byte[] b = transport.read(1);
        return (b[0] & 0x80) != 0;
    }

    private void requireGeneralCall() {
        if (generalCall == null)
            throw new IllegalStateException("General Call transport not configured");
    }
}
