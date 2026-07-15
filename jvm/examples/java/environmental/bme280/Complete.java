///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.environmental.Bme280Full;
import it.uhde.periph.transport.I2CTransport;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x76"));
        try (var transport = new I2CTransport(bus, addr)) {             // open I²C bus, (bus, address=0x76) → I2CTransport
            var sensor = new Bme280Full(transport);                      // construct driver, verifies chip ID and loads calibration, (transport) → Bme280Full
            int cid = sensor.chipId();                                  // read chip ID, () → int
                                                                         // returns 0x60 for BME280
            sensor.configure(Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.MODE_SLEEP, Bme280Full.FILTER_OFF, Bme280Full.T_SB_0_5_MS);  // configure chip, (osrsT 0–5, osrsP 0–5, osrsH 0–5, mode 0/1/3, filter 0–4, tSb 0–7) → void
                                                                         // writes ctrl_hum, config, ctrl_meas in correct order
            sensor.setOversampling(Bme280Full.OSRS_X4, Bme280Full.OSRS_X2, Bme280Full.OSRS_X1);  // set oversampling, (osrsT 0–5, osrsP 0–5, osrsH 0–5) → void
                                                                         // humidity update requires ctrl_meas write to latch
            sensor.setMode(Bme280Full.MODE_FORCED);                    // set power mode, (mode 0/1/3) → void
            sensor.setFilter(Bme280Full.FILTER_4);                     // set IIR filter, (coeff 0–4) → void
                                                                         // suppresses short-term pressure disturbances
            sensor.setStandby(Bme280Full.T_SB_125_MS);                 // set standby time, (tSb 0–7) → void
                                                                         // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
            int st = sensor.status();                                   // read status register, () → int
            double t = sensor.temperature();                            // read temperature, () → double °C
            double p = sensor.pressure();                               // read pressure, () → double hPa
            double h = sensor.humidity();                               // read humidity, () → double %RH
            double alt = sensor.altitude();                             // compute altitude, (seaLevelHpa=1013.25) → double m
                                                                         // uses barometric formula to convert pressure to metres
            double slp = sensor.seaLevelPressure(alt);                  // compute sea-level pressure, (altitudeM) → double hPa
            double dp = sensor.dewPoint();                              // compute dew point, () → double °C
                                                                         // Magnus-Tetens approximation from current T and RH
            sensor.reset();                                             // soft reset chip, () → void
                                                                         // re-reads calibration and re-applies configuration
            System.out.printf("T=%.1f C, P=%.1f hPa, RH=%.1f %%RH, alt=%.1f m, slp=%.1f hPa, dp=%.1f C%n", t, p, h, alt, slp, dp);
        }
    }
}
