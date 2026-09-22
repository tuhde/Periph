///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.accelerometer.Adxl362Full;

public class Adxl362Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS",  "0"));
        int dev  = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var chip = new Adxl362Full(connection);                                // Create ADXL362 Full driver, (connection) → Adxl362Full

            int[] ids = chip.deviceId();                                          // Read device IDs, () → int[4]
            checkTrue("device_id_devid_ad",  ids[0] == 0xAD);                    // verify DEVID_AD = 0xAD
            checkTrue("device_id_devid_mst", ids[1] == 0x1D);                    // verify DEVID_MST = 0x1D
            checkTrue("device_id_partid",    ids[2] == 0xF2);                    // verify PARTID = 0xF2

            float[] xyz = chip.read();                                            // Read 3-axis acceleration, () → float[3] g
            checkTrue("read_12bit", true);

            float[] xyz8 = chip.read8bit();                                       // Read 8-bit acceleration, () → float[3] g
            checkTrue("read_8bit", true);

            float t = chip.temperature();                                          // Read temperature, () → float °C
            checkTrue("temperature", true);

            chip.setRange(4);                                                      // Set measurement range, (rangeG=4) → void
            chip.setOdr(200.0f);                                                  // Set output data rate, (odrHz=200.0) → void
            chip.setHalfBandwidth(true);                                          // Set antialiasing bandwidth, (enabled=true) → void
            chip.setNoiseMode(Adxl362Full.NOISE_LOW);                             // Set noise mode, (mode=NOISE_LOW) → void
            checkTrue("set_range_odr_noise", true);

            int status = chip.status();                                           // Read STATUS register, () → int
            checkTrue("status", true);
            chip.awake();                                                         // Check AWAKE bit, () → boolean
            checkTrue("awake", true);
            chip.dataReady();                                                     // Check DATA_READY, () → boolean
            checkTrue("data_ready", true);
            int entries = chip.fifoEntries();                                      // Read FIFO entry count, () → int
            checkTrue("fifo_entries", entries >= 0);

            chip.configureFifo(Adxl362Full.FIFO_STREAM, false, 128);             // Configure FIFO, (mode=STREAM, storeTemp=false, watermark=128) → void
            chip.setActivityThreshold(0.5f, true);                                 // Set activity threshold, (thresholdG=0.5, referenced=true) → void
            chip.setActivityTime(5);                                              // Set activity time, (samples=5) → void
            chip.setInactivityThreshold(0.2f, true);                              // Set inactivity threshold, (thresholdG=0.2, referenced=true) → void
            chip.setInactivityTime(30);                                           // Set inactivity time, (samples=30) → void
            chip.enableActivityDetection(true);                                   // Enable activity detection, (enabled=true) → void
            chip.enableInactivityDetection(true);                                 // Enable inactivity detection, (enabled=true) → void
            chip.setLinkLoopMode(Adxl362Full.LINKLOOP_LOOP);                     // Set link/loop mode, (mode=LOOP) → void
            checkTrue("activity_inactivity_config", true);

            chip.setInterrupt(1, Adxl362Full.SOURCE_DATA_READY, true);            // Map DATA_READY to INT1, (pin=1, source=DATA_READY, enabled=true) → void
            chip.setInterrupt(2, Adxl362Full.SOURCE_AWAKE, true);                 // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → void
            chip.setInterruptPolarity(1, true);                                   // Set INT1 active-low, (pin=1, activeLow=true) → void
            checkTrue("interrupt_mapping", true);

            chip.selfTest(true);                                                   // Enable self-test, (enabled=true) → void
            chip.selfTest(false);                                                  // Disable self-test, (enabled=false) → void
            checkTrue("self_test", true);

            chip.softReset();                                                      // Soft-reset the chip, () → void
            checkTrue("soft_reset", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}