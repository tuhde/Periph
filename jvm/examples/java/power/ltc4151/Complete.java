///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ltc4151Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x6A)) { // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
            var ltc = new Ltc4151Full(connection, 0.02);    // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Full

            System.out.println(ltc.voltage());              // read supply voltage, () → double V
                                                            // VIN code × 25 mV
            System.out.println(ltc.current());              // read load current, () → double A
                                                            // sense voltage / rSense
            System.out.println(ltc.power());                // read load power, () → double W
            System.out.println(ltc.senseVoltage());         // read SENSE+ − SENSE−, () → double V
            System.out.println(ltc.adinVoltage());          // read ADIN pin voltage, () → double V
            var all = ltc.readAll();                        // consistent read of all channels, () → Ltc4151Full.Readings
                                                            // one block read of registers 0x00–0x05
            System.out.println(all.vin + " V, " + all.current + " A, " + all.power + " W");
            System.out.println(ltc.readRaw(Ltc4151Full.VIN));  // read raw 12-bit code, (channel 0–2) → int
                                                            // SENSE = 0, VIN = 1, ADIN = 2

            ltc.setSenseResistor(0.01);                     // change sense resistor, (ohms) → void
            System.out.println(ltc.getSenseResistor());     // read sense resistor, () → double Ω
            ltc.setSenseResistor(0.02);

            System.out.println(ltc.snapshot(Ltc4151Full.SENSE));  // one snapshot conversion, (channel 0–2) → double V
                                                            // returns to continuous scan; IOException after 100 ms
            System.out.println(ltc.isSnapshotMode());       // snapshot mode active, () → boolean
            System.out.println(ltc.isBusy(Ltc4151Full.VIN));  // channel busy, (channel 0–2) → boolean
            ltc.setContinuous();                            // back to continuous scan, () → void

            ltc.setStuckBusTimer(true);                     // stuck-bus reset timer, (enabled) → void
            ltc.setPageRw(true);                            // I²C page read/write, (enabled) → void
            System.out.printf("0x%02X%n", ltc.readControl());  // read CONTROL, () → int
            ltc.writeControl(0x0C);                         // write CONTROL, (value) → void
                                                            // test mode bit 4 is always masked to 0

            System.out.println(Ltc4151Full.temperatureFromAdin(900, 1200));  // NTC example circuit, (adinCode, vinCode) → double °C
        }
    }
}
