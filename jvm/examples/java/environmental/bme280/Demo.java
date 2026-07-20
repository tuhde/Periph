///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.chips.environmental.Bme280Full;
import it.uhde.periph.transport.I2CTransport;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x76"));
        try (var transport = new I2CTransport(bus, addr)) {

            // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
            // BME280 datasheet "weather monitoring" preset: minimum power,
            // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between
            // samples to demonstrate battery-friendly indoor monitoring.
            var sensor = new Bme280Full(transport);                  // construct driver, verifies chip ID and loads calibration, (transport) → Bme280Full
            sensor.configure(Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.OSRS_X1, Bme280Full.MODE_FORCED, Bme280Full.FILTER_OFF, Bme280Full.T_SB_0_5_MS);  // configure chip, (osrsT=×1, osrsP=×1, osrsH=×1, mode=forced, filter=off, tSb=0) → void

            int nSamples = 10;
            for (int n = 0; n < nSamples; n++) {
                double t = sensor.temperature();                    // read temperature, () → double °C
                double p = sensor.pressure();                       // read pressure, () → double hPa
                double h = sensor.humidity();                       // read humidity, () → double %RH
                double a = sensor.altitude();                       // compute altitude, (seaLevelHpa=1013.25) → double m
                double d = sensor.dewPoint();                       // compute dew point, () → double °C
                System.out.printf("%d: %.1f C, %.1f %%RH, %.1f hPa, dew=%.1f C, alt=%.1f m%n", n, t, h, p, d, a);
                Thread.sleep(1000);
            }

            // --- Half-way: breathe gently on the sensor for 3 seconds ---
            // User exposes the sensor to humid exhaled air; humidity climbs
            // from ~40 %RH toward ~80 %RH, dew point spikes toward ambient
            // temperature, pressure stays flat, temperature rises only
            // slightly. Demonstrates the humidity channel's response and
            // the dew-point alarm use case.
            System.out.println("--- Breathe gently on the sensor for 3 seconds ---");
            Thread.sleep(3000);
            {
                double t = sensor.temperature();                    // read temperature, () → double °C
                double p = sensor.pressure();                       // read pressure, () → double hPa
                double h = sensor.humidity();                       // read humidity, () → double %RH
                double d = sensor.dewPoint();                       // compute dew point, () → double °C
                System.out.printf("after breath: %.1f C, %.1f %%RH, %.1f hPa, dew=%.1f C%n", t, h, p, d);
            }
        }
    }
}
