///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Rrh62000Full;

public class Rrh62000Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x69").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Rrh62000Full(connection);
            sensor.init();
            checkTrue("firmware_version", sensor.firmwareVersion().length == 2);
            checkTrue("algorithm_version", sensor.algorithmVersion().length == 3);
            checkTrue("unique_id", sensor.uniqueId().length == 6);
            var r = sensor.read();
            checkTrue("read_pm", r.pm2_5UgM3() >= 0 && r.pm2_5UgM3() <= 1000);
            checkTrue("read_temperature", r.temperatureC() >= -20 && r.temperatureC() <= 60);
            checkTrue("read_humidity", r.humidityRh() >= 0 && r.humidityRh() <= 100);
            checkTrue("read_iaq", r.iaq() >= 0 && r.iaq() <= 5);
            var d = sensor.readAll();
            checkTrue("read_all_nc", d.particles().nc0_3() >= d.particles().nc0_5());
            checkTrue("read_status_fan_ok", !sensor.readStatus().fan());
            checkTrue("read_particles", sensor.readParticles() != null);
            sensor.dataReady();
            checkTrue("data_ready", true);
            int avg = sensor.getMovingAverage();
            sensor.setMovingAverage(20);
            checkTrue("moving_average", sensor.getMovingAverage() == 20);
            sensor.setMovingAverage(avg);
            int fan = sensor.getFanSpeed();
            sensor.setFanSpeed(70);
            checkTrue("fan_speed", sensor.getFanSpeed() == 70);
            sensor.setFanSpeed(fan);
            int interval = sensor.getCleaningInterval();
            sensor.setCleaningInterval(86400);
            checkTrue("cleaning_interval", sensor.getCleaningInterval() == 86400);
            sensor.setCleaningInterval(interval);
            int t = sensor.getCleaningTime();
            sensor.setCleaningTime(12);
            checkTrue("cleaning_time", sensor.getCleaningTime() == 12);
            sensor.setCleaningTime(t);
            checkTrue("mox_resistance", sensor.readMoxResistance() > 0);
            sensor.cleaningDone();
            checkTrue("cleaning_done", true);
            sensor.sleep();
            Thread.sleep(3000);
            sensor.wake();
            checkTrue("wake_read", sensor.read() != null);
            sensor.softReset();
            checkTrue("soft_reset_read", sensor.read() != null);
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
