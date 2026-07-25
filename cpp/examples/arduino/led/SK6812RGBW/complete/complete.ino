#include <SPI.h>
#include "NeoPixelTransport.h"
#include "SK6812RGBW.h"

NeoPixelTransport transport(SPI);               // Create NeoPixel transport, (spi=SPIClass&)
SK6812RGBWFull strip(transport, 8);            // Create SK6812RGBW full driver, (transport, n=8 pixels)

void setup() {
    Serial.begin(115200);
    SPI.begin();
}

void loop() {
    // fill — set all pixels and send immediately
    strip.fill(255, 0, 0);                      // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // stores GRBW in buffer and calls transport.write()
    delay(500);

    // fill with white channel
    strip.fill(0, 0, 0, 255);                   // Fill all pixels using W channel, (r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // w=255 activates dedicated white LED element; RGB channels stay dark
    delay(500);

    // set individual pixels then show
    strip.set_pixel(0, 255, 0, 0);             // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    strip.set_pixel(1, 0, 255, 0);             // Set pixel 1 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    strip.set_pixel(2, 0, 0, 255);             // Set pixel 2 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    strip.set_pixel(3, 0, 0, 0, 255);          // Set pixel 3 to white in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // w=255 lights the dedicated white element; RGB remain off
    strip.show();                               // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
    delay(500);

    // brightness — global scale applied at show() time
    strip.set_brightness(64);                   // Set global brightness, (value=0–255) → void
                                                // stored value is scaled: sent = stored * brightness / 255
    strip.show();                               // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
    delay(500);
    strip.set_brightness(255);                  // Set global brightness, (value=0–255) → void
                                                // stored value is scaled: sent = stored * brightness / 255

    // fill_hsv — fill all pixels from HSV colour (white=0)
    strip.fill_hsv(0.0f, 1.0f, 1.0f);         // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                // converts HSV to RGB (w=0) then calls fill(); hue 0.0 = red
    delay(500);
    strip.fill_hsv(0.333f, 1.0f, 1.0f);       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                // converts HSV to RGB (w=0) then calls fill(); hue 0.333 = green
    delay(500);
    strip.fill_hsv(0.667f, 1.0f, 1.0f);       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                // converts HSV to RGB (w=0) then calls fill(); hue 0.667 = blue
    delay(500);

    // rotate — shift pixel buffer left, then show
    strip.set_pixel(0, 255, 0, 0);             // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    for (size_t i = 1; i < 8; i++) {
        strip.set_pixel(i, 0, 0, 0, 0);        // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
                                                // writes G,R,B,W bytes into internal buffer at position index*4
    }
    strip.show();                               // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
    delay(500);
    for (int i = 0; i < 7; i++) {
        strip.rotate(1);                        // Rotate pixel buffer left, (steps=1) → void
                                                // shifts buffer by steps whole-pixel (4-byte) positions; wraps around; does not send
        strip.show();                           // Transmit buffer to strip, () → void
                                                // applies brightness scaling then calls transport.write()
        delay(200);
    }

    strip.off();                                // Turn off all pixels, () → void
                                                // equivalent to fill(0, 0, 0, 0)
    delay(1000);
}
