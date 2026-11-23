///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-java:1.5.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.Is31fl3731Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x74"));
        try (var connection = new I2CConnection(bus, addr)) {             // open I²C bus, (bus, address=0x74) → I2CConnection
            var matrix = new Is31fl3731Full(connection);                // construct driver, (connection) → Is31fl3731Full

            matrix.clear();                                             // switch all LEDs of the drawing frame off, () → void
            matrix.setPixel(2, 5, 200);                                 // set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → void
            System.out.println(matrix.getPixel(2, 5));                  // read a pixel from the shadow copy, (row, col) → int 0–255
            byte[] ramp = new byte[144];
            for (int i = 0; i < 144; i++) ramp[i] = (byte) i;
            matrix.setPixels(ramp);                                     // write a whole frame, (data 144 bytes, row-major) → void
            matrix.fill(40);                                            // set every LED's PWM brightness, (brightness 0–255) → void
            matrix.setLedEnabled(0, 0, false);                          // LED-control (on/off) bit, (row, col, on) → void
            matrix.setLedEnabled(0, 0, true);
            matrix.setBlink(1, 1, true);                                // blink-enable bit of one LED, (row, col, on) → void
            matrix.setBlinkAll(false);                                  // blink-enable bit of every LED, (on) → void
            matrix.enableBlink(true, 2);                                // blink on, (enabled, period 0–7 × 0.27 s) → void
                                                                        // 0.54 s period, 50 % duty
            Thread.sleep(2000);
            matrix.enableBlink(false, 1);
            matrix.selectFrame(1);                                      // frame subsequent drawing writes to, (frame 0–7) → void
            matrix.fill(100);
            matrix.copyFrame(1, 2);                                     // copy PWM values between frames, (src, dst) → void
            matrix.clearFrame(2);                                       // re-initialise one frame, (frame 0–7) → void
            matrix.clearAllFrames();                                    // re-initialise all eight frames, () → void
            matrix.selectFrame(0);
            matrix.showFrame(0);                                        // display a frame in Picture mode, (frame 0–7) → void
            matrix.setPictureMode();                                    // Picture mode, () → void
            matrix.startAutoPlay(2, 1, 110, 0);                         // Auto Frame Play, (frames 0–7, loops 0–7, delayMs, startFrame) → void
                                                                        // frames/loops 0 = all/endless; plays 2 frames once
            Thread.sleep(1000);
            System.out.println(matrix.frameState());                    // read FRAME_STATE, () → int
            System.out.println(matrix.currentFrame());                  // frame being displayed, () → int 0–7
            System.out.println(matrix.movieFinished());                 // movie-finished flag (clears it), () → boolean
            matrix.stopAutoPlay();                                      // back to Picture mode on the current frame, () → void
            matrix.setBreath(true, 3, 3, 2);                            // breath function, (enabled, fadeIn, fadeOut, extinguish 0–7) → void
            Thread.sleep(3000);
            matrix.setBreath(false, 0, 0, 0);
            matrix.setIntensityControl(false);                          // share frame 1's PWM with all frames, (useFrame1) → void
            matrix.setAudioGain(6, true, false);                        // audio gain, (gainDb 0–21 step 3, agc, fast) → void
            matrix.setAudioRate(10);                                    // audio ADC interval, (a 0–255, × 46 µs) → void
            matrix.setAudioSync(false);                                 // audio intensity modulation, (enabled) → void
            matrix.setAudioMode(false);                                 // Audio Frame Play on/off, (enabled) → void
            System.out.println(matrix.readFunctionRegister(0x03));      // raw function-page read, (reg 0–0x0C) → int
            matrix.writeFunctionRegister(0x03, 0x0B);                   // raw function-page write, (reg, value) → void
            System.out.println(matrix.pollInterrupt());                 // FRAME_STATE, clears INT, () → int
            matrix.onInterrupt(status -> System.out.println("movie finished " + status));// callback when a movie ends, (callback) → void
            matrix.offInterrupt();                                      // unsubscribe, () → void
            matrix.shutdown(true);                                      // software shutdown, (on) → void
            matrix.shutdown(false);                                     // back to normal operation
        }
    }
}
