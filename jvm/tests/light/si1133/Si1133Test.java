///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1133Full;

public class Si1133Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean in24(int v) {
        return v >= -0x800000 && v <= 0x7FFFFF;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x55").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1133Full(connection);
            sensor.init();
            var id = sensor.readId();
            checkTrue("part_id", id.partId() == 0x33);
            checkTrue("hw_id_impl", (id.hwId() & 0x1F) == 0x03);
            checkTrue("response_no_error", !sensor.readResponse().error());
            var m = sensor.read();
            checkTrue("read_uv_range", in24(m.uv()));
            checkTrue("read_white_positive_in_light", m.white() > 0);
            checkTrue("read_uv_scratch", in24(sensor.readUv()));
            checkTrue("read_white_scratch", sensor.readWhite() > 0);
            checkTrue("read_ir_small", in24(sensor.readIr(Si1133Full.MUX_SMALL_IR)));
            checkTrue("read_ir_large", in24(sensor.readIr(Si1133Full.MUX_LARGE_IR)));
            sensor.configureChannel(2, Si1133Full.MUX_LARGE_IR, 5, 0, 0, false, false, 0);
            sensor.enableChannels(0x07);
            int[] v = sensor.readChannels();
            checkTrue("read_channels_count", v.length == 3);
            checkTrue("read_channels_16bit", v.length == 3 && v[2] >= 0 && v[2] <= 0xFFFF);
            sensor.writeParameter(0x1C, 0x05);
            checkTrue("parameter_roundtrip", sensor.readParameter(0x1C) == 0x05);
            checkTrue("chan_list_readback", sensor.readParameter(0x01) == 0x07);
            sensor.setThreshold(0, 1000);
            sensor.setChannelThreshold(0, 1);
            checkTrue("threshold_readback", sensor.readParameter(0x25) == 0x03 && sensor.readParameter(0x26) == 0xE8);
            sensor.setChannelThreshold(0, 0);
            sensor.enableChannels(0x03);
            sensor.dataReady();
            sensor.startAutonomous(0.1, 1, 1, 1);
            Thread.sleep(500);
            checkTrue("autonomous_irq", (sensor.pollInterrupt() & 0x03) != 0);
            checkTrue("autonomous_values", sensor.readChannels(false).length == 2);
            sensor.pause();
            sensor.reset();
            checkTrue("reset_sleep", (sensor.readResponse().state() & Si1133Full.RESP_SLEEP) != 0);
            checkTrue("read_after_reset", sensor.read().white() > 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
