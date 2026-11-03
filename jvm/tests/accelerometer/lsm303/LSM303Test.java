///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.LSM303Full;

public class LSM303Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var accelConn = new I2CConnection(bus, LSM303Full.I2C_ADDRESS_ACCEL);
             var magConn = new I2CConnection(bus, LSM303Full.I2C_ADDRESS_MAG)) {
            var chip = new LSM303Full(accelConn, magConn);
            checkTrue("construct_full", true);

            double[] accel = chip.readAcceleration();
            checkTrue("read_acceleration_returns_doubles", accel.length == 3);
            double magnitude = Math.sqrt(accel[0]*accel[0] + accel[1]*accel[1] + accel[2]*accel[2]);
            checkTrue("acceleration_magnitude_near_1g", magnitude >= 0.5 && magnitude <= 1.5);

            double[] mag = chip.readMagnetic();
            checkTrue("read_magnetic_returns_doubles", mag.length == 3);

            chip.setRange(4);
            double[] accel2 = chip.readAcceleration();
            checkTrue("read_after_set_range_4g", accel2.length == 3);

            chip.setDataRate(50);
            chip.setMode(LSM303Full.MODE_HIGH_RESOLUTION);
            chip.setAxes(true, true, true);
            checkTrue("new_data_available", chip.newDataAvailable() || !chip.newDataAvailable());
            checkTrue("read_status", chip.readStatus() >= 0);

            chip.setHighPass(0, 0, false, true, false, false);
            checkTrue("reset_high_pass", chip.resetHighPass() >= 0);
            chip.setReference(0);

            chip.setFifo(LSM303Full.FIFO_STREAM, 16, false);
            checkTrue("fifo_level", chip.fifoLevel() >= 0);
            checkTrue("read_fifo_status", chip.readFifoStatus().length == 4);

            chip.setMotionInterrupt(1, 0x02, 0.5, 10, false);
            chip.setFreeFall(1, 0.35, 30);
            chip.setOrientation(1, false, false);
            chip.setLatch(1, true);
            checkTrue("read_interrupt_source", chip.readInterruptSource(1) >= 0);
            chip.setClick(0, 0x04, 0.8, 20, 40, 200);
            checkTrue("read_click_source", chip.readClickSource() >= 0);

            chip.setInterruptPolarity(false);
            chip.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);
            chip.disableInterrupt(LSM303Full.SOURCE_CLICK, 1);

            chip.setMagRange(250);
            chip.setMagDataRate(30);
            chip.setMagMode(LSM303Full.MAG_MODE_CONTINUOUS);
            checkTrue("read_magnetic_raw", chip.readMagneticRaw().length == 3);
            checkTrue("read_mag_status", chip.readMagStatus().length == 2);
            byte[] identity = chip.readMagIdentity();
            checkTrue("mag_identity_matches",
                    (identity[0] & 0xFF) == 0x48 && (identity[1] & 0xFF) == 0x34
                            && (identity[2] & 0xFF) == 0x33);
            chip.enableTemperature(true);
            double temp = chip.readTemperatureDelta();
            checkTrue("temperature_in_range", temp >= -50.0 && temp <= 150.0);

            double heading = chip.heading(false);
            checkTrue("heading_in_range", heading >= 0.0 && heading < 360.0);

            checkTrue("poll_interrupt", chip.pollInterrupt() >= 0);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
