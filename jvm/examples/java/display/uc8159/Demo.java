///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.PollingInputPin;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.Uc8159Full;

// UC8159 demo example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Draws a status card and prints the refresh time (the number the spec's Timing Constraints
// are missing).

public class Demo {
    public static void main(String[] args) throws Exception {
        var dc = new SysfsOutputPin(25);                                                         // create D/C# pin, (gpio=25) → OutputPin
        var res = new SysfsOutputPin(17);                                                        // create RES# pin, (gpio=17) → OutputPin
        var busy = new PollingInputPin("/dev/gpiochip0", 24);                                    // create BUSY pin, (chip, line=24) → InputPin
        var connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, busy, WireMode.SIMPLEX); // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
        var epd = new Uc8159Full(connection);                                                       // construct driver, (connection, busyTimeoutMs=60000) → Uc8159Full

        // --- Compose the colour chart in the framebuffer ---
        // White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
        // one-pixel black frame inset by 4 px (a frame on the very edge is hidden by the bezel).
        epd.clear(Uc8159Full.WHITE);                                                                // fill framebuffer, (color=WHITE) → void
        for (int c = 0; c < 7; c++) {
            epd.drawRect(20 + c * 80, 40, 80, 368, c, true);  // draw rectangle, (x, y, w, h, color, fill) → void
        }
        epd.drawRect(4, 4, 592, 440, Uc8159Full.BLACK, false);                                      // draw rectangle, (x, y, w, h, color, fill) → void

        // --- Refresh and time it ---
        // show() blocks for the whole DTM1/PON/DRF/POF sequence: about 30 s.
        long start = System.currentTimeMillis();
        epd.show();                                                                                 // refresh panel, () → void
        System.out.println("refresh took " + (System.currentTimeMillis() - start) / 1000.0 + " s");

        // --- Save the image and power down ---
        // The panel keeps the picture without power; deep sleep stops the controller drawing current.
        epd.sleep();                                                                                // enter deep sleep, () → void
        connection.close();
    }
}
