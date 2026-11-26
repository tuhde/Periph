///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Ccs811Full;
import java.util.Arrays;

public class Complete {

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5A").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Ccs811Full(connection);                                                // Create CCS811 driver, (connection) → Ccs811Full
                                                                                                    // stores the connection only; no bus traffic yet
            sensor.init();                                                                          // Check HW_ID, start application, mode 1, () → void
                                                                                                    // HW_ID 0x81, APP_START if the chip is in boot mode, one sample per second
            System.out.println(Arrays.toString(sensor.hardwareVersion()));                          // Hardware ID and version, () → int[] {hwId, hwVersion}
                                                                                                    // HW_ID is 0x81, HW_VERSION 0x1X
            System.out.println(sensor.firmwareVersion());                                           // Firmware versions, () → FirmwareVersion {boot, app}
                                                                                                    // bootloader and application firmware, each major.minor.trivial
            System.out.printf("status 0x%02X%n", sensor.status());                                  // STATUS register, () → int
                                                                                                    // bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR
            System.out.printf("error 0x%02X%n", sensor.error());                                    // ERROR_ID register, () → int
                                                                                                    // zero unless STATUS.ERROR is set

            System.out.println(sensor.read());                                                      // Next sample, () → Measurement {eco2Ppm ppm, tvocPpb ppb}
                                                                                                    // waits for DATA_READY, then reads eCO2 and TVOC
            System.out.println(sensor.readEco2());                                                  // eCO2 only, () → int ppm
                                                                                                    // equivalent CO2 estimated from the VOC level, 400–8192 ppm
            System.out.println(sensor.readTvoc());                                                  // TVOC only, () → int ppb
                                                                                                    // total volatile organic compounds, 0–1187 ppb
            System.out.println(Arrays.toString(sensor.readRaw()));                                  // Raw sensor data, () → double[] {current µA, voltage V}
                                                                                                    // sense current and the voltage across the sensor
            System.out.println(sensor.dataReady());                                                 // New sample waiting, () → boolean
                                                                                                    // false right after a read, because reading the result clears DATA_READY

            sensor.setEnvironment(45.0, 22.5);                                                      // Compensation data, (humidityRh %, temperatureC °C) → void
                                                                                                    // real ambient values improve accuracy; the chip assumes 50 %RH and 25 °C otherwise
            System.out.println(Arrays.toString(sensor.readNtc()));                                  // NTC circuit voltages, () → int[] {vRefMv mV, vNtcMv mV}
                                                                                                    // only meaningful with a reference resistor and NTC wired to the AUX pin
            try {
                System.out.println(sensor.ntcResistance(100000));                                   // NTC resistance, (rRefOhm Ω) → double Ω
                                                                                                    // V_NTC × R_REF / V_REF; map it to °C with the thermistor's curve
            } catch (java.io.IOException e) {
                System.out.println("no NTC circuit: " + e.getMessage());
            }

            sensor.setThresholds(1000, 2000, 50);                                                   // Threshold boundaries, (lowMediumPpm, mediumHighPpm, hysteresisPpm) → void
                                                                                                    // eCO2 zones used by the threshold interrupt
            int baseline = sensor.getBaseline();                                                    // Algorithm baseline, () → int
                                                                                                    // save it to non-volatile storage about once a day
            System.out.printf("baseline 0x%04X%n", baseline);
            sensor.setBaseline(baseline);                                                           // Restore baseline, (baseline) → void
                                                                                                    // restore a saved value only after about 20 min of warm-up

            sensor.enableInterrupt(Ccs811Full.SOURCE_DATA_READY);                                   // nINT on new data, (source) → void
                                                                                                    // sets INT_DATARDY and keeps the drive mode
            sensor.onInterrupt(st -> System.out.printf("interrupt status 0x%02X%n", st));           // Subscribe, (callback) → void
                                                                                                    // without an INT pin on the connection the driver polls STATUS
            Thread.sleep(3000);
            sensor.offInterrupt();                                                                  // Unsubscribe, () → void
                                                                                                    // stops the edge handler or polling thread
            System.out.printf("poll 0x%02X%n", sensor.pollInterrupt());                             // STATUS without releasing nINT, () → int
                                                                                                    // DATA_READY shows whether a sample is pending
            sensor.disableInterrupt(Ccs811Full.SOURCE_DATA_READY);                                  // nINT off, (source) → void
                                                                                                    // clears INT_DATARDY

            sensor.setMode(4);                                                                      // Drive mode, (mode 0–4) → void
                                                                                                    // mode 4: raw data every 250 ms, no eCO2/TVOC updates
            Thread.sleep(500);
            System.out.println(Arrays.toString(sensor.readRaw()));                                  // Raw sensor data, () → double[] {current µA, voltage V}
                                                                                                    // RAW_DATA keeps updating in mode 4
            sensor.setMode(0);                                                                      // Drive mode, (mode 0–4) → void
                                                                                                    // idle before switching to another mode
            sensor.setMode(1);                                                                      // Drive mode, (mode 0–4) → void
                                                                                                    // back to one sample per second

            sensor.softReset();                                                                     // Software reset, () → void
                                                                                                    // SW_RESET into boot mode, then APP_START and mode 1 again
        }
    }
}
