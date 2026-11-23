///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.Is31fl3731Minimal;
import it.uhde.periph.chips.led.Is31fl3731Full;

public class Is31fl3731Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    interface Action { void run() throws Exception; }

    static boolean throwsIllegal(Action a) throws Exception {
        try { a.run(); } catch (IllegalArgumentException e) { return true; }
        return false;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x74"));

        try (var connection = new I2CConnection(bus, addr)) {
            var matrix = new Is31fl3731Minimal(connection);
            checkTrue("constants", Is31fl3731Minimal.ROWS == 9 && Is31fl3731Minimal.COLS == 16);
            matrix.setPixel(0, 0, 255);
            matrix.setPixel(8, 15, 64);
            checkTrue("set_pixel", true);
            checkTrue("set_pixel_range", throwsIllegal(() -> matrix.setPixel(9, 0, 0)));
            matrix.fill(32);
            checkTrue("fill", true);
            matrix.clear();
            checkTrue("clear", true);

            var full = new Is31fl3731Full(connection);
            full.setPixel(3, 4, 99);
            checkTrue("get_pixel", full.getPixel(3, 4) == 99 && full.getPixel(3, 5) == 0);
            byte[] ramp = new byte[144];
            for (int i = 0; i < 144; i++) ramp[i] = (byte) i;
            full.setPixels(ramp);
            checkTrue("set_pixels", true);
            full.setLedEnabled(0, 0, false);
            full.setLedEnabled(0, 0, true);
            checkTrue("set_led_enabled", true);
            full.setBlink(1, 1, true);
            full.setBlinkAll(true);
            full.enableBlink(true, 2);
            full.enableBlink(false, 1);
            full.setBlinkAll(false);
            checkTrue("blink", true);
            full.selectFrame(1);
            full.fill(10);
            full.copyFrame(1, 2);
            full.showFrame(2);
            full.selectFrame(0);
            full.showFrame(0);
            checkTrue("frames", true);
            full.clearFrame(1);
            full.clearAllFrames();
            checkTrue("clear_frames", true);
            full.setPictureMode();
            checkTrue("set_picture_mode", true);
            full.startAutoPlay(2, 1, 110, 0);
            full.currentFrame();
            full.movieFinished();
            full.stopAutoPlay();
            checkTrue("auto_play", true);
            full.setAudioGain(6, true, false);
            full.setAudioRate(10);
            full.setAudioSync(false);
            full.setAudioMode(false);
            checkTrue("audio", true);
            full.setBreath(true, 2, 2, 1);
            full.setBreath(false, 0, 0, 0);
            checkTrue("set_breath", true);
            full.setIntensityControl(true);
            full.setIntensityControl(false);
            checkTrue("set_intensity_control", true);
            full.writeFunctionRegister(0x03, 0x05);
            full.readFunctionRegister(0x03);
            checkTrue("function_register", true);
            full.pollInterrupt();
            checkTrue("poll_interrupt", true);
            full.shutdown(true);
            full.shutdown(false);
            checkTrue("shutdown", true);
            full.clear();
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
