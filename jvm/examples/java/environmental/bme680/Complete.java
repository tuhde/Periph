///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Bme680Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x76)) {        // open I²C bus 1, device 0x76, (bus, address=0x76) → I2CTransport
            var sensor = new Bme680Full(transport);                      // construct driver, verifies chip ID and loads calibration, (transport) → Bme680Full

            int id = sensor.chipId();                                    // read chip ID register 0xD0, () → int
                                                                         // returns 0x61 for BME680; useful for confirming the device is present
            System.out.printf("chip ID: 0x%02X%n", id);

            int st = sensor.status();                                    // read status register 0x1D, () → int
                                                                         // bit 7 = new_data, bit 6 = gas_measuring, bit 5 = measuring
            System.out.printf("status: 0x%02X%n", st);

            sensor.configure(                                            // configure oversampling, mode, filter, (osrsT, osrsP, osrsH, mode, filter) → void
                Bme680Full.OSRS_X4, Bme680Full.OSRS_X4,                 // temperature ×4, pressure ×4 oversampling
                Bme680Full.OSRS_X2,                                      // humidity ×2 oversampling
                Bme680Full.MODE_FORCED,                                  // forced mode: one measurement per call
                Bme680Full.FILTER_3);                                    // IIR filter coefficient 3
                                                                         // reduces high-frequency noise while retaining step-change response

            double t = sensor.temperature();                             // read temperature, () → double °C
                                                                         // triggers forced-mode TPHG cycle, applies Bosch integer compensation
            System.out.printf("temperature: %.2f °C%n", t);

            double p = sensor.pressure();                                // read pressure, () → double hPa
                                                                         // re-reads temperature ADC to refresh tFine, then compensates pressure
            System.out.printf("pressure: %.2f hPa%n", p);

            double h = sensor.humidity();                                // read humidity, () → double %RH
                                                                         // re-reads temperature ADC to refresh tFine, then compensates humidity
            System.out.printf("humidity: %.1f %%RH%n", h);

            double g = sensor.gasResistance();                           // read gas resistance, () → double Ω
                                                                         // computes resistance from gas ADC and range code; NaN if heater unstable
            System.out.printf("gas resistance: %.0f Ω%n", g);

            double[] all = sensor.readAll();                             // read all four values from one TPHG cycle, () → double[4]
                                                                         // [0]=°C, [1]=hPa, [2]=%RH, [3]=Ω; more efficient than four separate calls
            System.out.printf("readAll: %.2f °C  %.2f hPa  %.1f %%RH  %.0f Ω%n",
                    all[0], all[1], all[2], all[3]);

            boolean gv = sensor.gasValid();                              // check gas measurement validity, () → boolean
                                                                         // true if the gas ADC produced a real result (not a dummy slot)
            System.out.printf("gas valid: %b%n", gv);

            boolean hs = sensor.heaterStable();                          // check heater stability, () → boolean
                                                                         // true if the heater reached its target temperature within gas_wait
            System.out.printf("heater stable: %b%n", hs);

            sensor.setOversampling(                                      // update oversampling settings, (osrsT, osrsP, osrsH) → void
                Bme680Full.OSRS_X2, Bme680Full.OSRS_X2,                 // temperature ×2, pressure ×2
                Bme680Full.OSRS_X1);                                     // humidity ×1
                                                                         // preserves current mode bits; reduces measurement time
            sensor.setFilter(Bme680Full.FILTER_0);                      // set IIR filter coefficient, (coeff=0–7) → void
                                                                         // FILTER_0 disables the IIR filter entirely

            sensor.setHeater(320, 150);                                  // configure heater profile 0, (tempC °C, durationMs ms) → void
                                                                         // sets target temperature and duration, then selects profile 0
            sensor.setHeaterProfile(1, 200, 100);                       // configure heater profile 1, (index, tempC °C, durationMs ms) → void
                                                                         // stores profile 1 without activating it
            sensor.selectHeaterProfile(1);                               // activate heater profile 1, (index=0–9) → void
                                                                         // subsequent measurements use profile 1's heater settings
            sensor.selectHeaterProfile(0);                               // switch back to profile 0, (index=0–9) → void
                                                                         // profile 0 is the default 320 °C / 150 ms configuration

            sensor.setGasEnabled(false);                                 // disable gas conversion, (enabled) → void
                                                                         // skips the gas measurement phase to save power and time
            sensor.setGasEnabled(true);                                  // re-enable gas conversion, (enabled) → void
                                                                         // restores gas measurement in the forced-mode cycle

            sensor.setHeaterOff(true);                                   // disable heater via heat_off override, (off) → void
                                                                         // prevents heater activation regardless of profile settings
            sensor.setHeaterOff(false);                                  // re-enable heater, (off) → void
                                                                         // clears the heat_off override bit

            sensor.setAmbientTemperature(25.0);                          // override ambient temperature for heater calc, (tempC °C) → void
                                                                         // recomputes heater resistance register using the new ambient value

            sensor.reset();                                              // soft-reset the chip and reload calibration, () → void
                                                                         // writes 0xB6 to 0xE0, waits 2 ms, re-reads NVM, restores all registers

            System.out.printf("temperature after reset: %.2f °C%n", sensor.temperature()); // read temperature, () → double °C
                                                                                              // verifies the sensor is functional after reset
        }
    }
}
