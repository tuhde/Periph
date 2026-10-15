///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.PollingInputPin;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.Uc8276cMinimal;

// UC8276C minimal example: Waveshare 4.2inch e-Paper (B) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// Each refresh blocks for about 17 s.

public class Minimal {
    public static void main(String[] args) throws Exception {
        var dc = new SysfsOutputPin(25);                                                         // create D/C# pin, (gpio=25) → OutputPin
        var res = new SysfsOutputPin(17);                                                        // create RES# pin, (gpio=17) → OutputPin
        var busy = new PollingInputPin("/dev/gpiochip0", 24);                                    // create BUSY_N pin, (chip, line=24) → InputPin
        var connection = DisplayConnection.hardware(0, 0, 4000000, dc, res, busy, WireMode.SIMPLEX); // open display connection, (bus, device, speedHz, dc, res, busy, wireMode)
        var epd = new Uc8276cMinimal(connection);                                                // construct driver, (connection) → Uc8276cMinimal
        epd.clear(Uc8276cMinimal.WHITE);                                                         // fill framebuffer, (color=WHITE) → void
        epd.setPixel(10, 10, Uc8276cMinimal.BLACK);                                              // set one pixel, (x, y, color=BLACK) → void
        for (int x = 20; x < 120; x++) epd.setPixel(x, 40, Uc8276cMinimal.RED);                  // set one pixel, (x, y, color=BLACK) → void
        epd.show();                                                                              // refresh panel (about 17 s), () → void
        epd.sleep();                                                                             // enter deep sleep, () → void
        connection.close();
    }
}
