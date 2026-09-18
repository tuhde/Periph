// APA102 complete example — every API method demonstrated.
// go build ./go/examples/linux/led/apa102/complete/
package main

import (
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, _ := strconv.Atoi(os.Getenv("SPI_BUS"))
	device, _ := strconv.Atoi(os.Getenv("SPI_DEVICE"))

	conn, err := connection.NewSPIConnection(bus, device, 1_000_000, nil, nil)
	if err != nil {
		panic(err)
	}

	strip, err := led.NewAPA102Full(conn, 8)
	if err != nil {
		panic(err)
	}

	// fill — set all pixels and send immediately
	strip.Fill(255, 0, 0)                                        // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → error
																 // stores brightness/B/G/R in buffer and calls connection.Write()
	time.Sleep(500 * time.Millisecond)

	// set individual pixels then show
	strip.SetPixel(0, 255, 0, 0, 31)                             // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
																 // writes brightness, B, G, R bytes into internal buffer at position index*4
	strip.SetPixel(1, 0, 255, 0, 31)                             // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
																 // writes brightness, B, G, R bytes into internal buffer at position index*4
	strip.SetPixel(2, 0, 0, 255, 31)                             // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → ()
																 // writes brightness, B, G, R bytes into internal buffer at position index*4
	strip.Show()                                                 // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
	time.Sleep(500 * time.Millisecond)

	// set_pixels — write multiple pixels at once
	strip.SetPixels([][]uint8{                                    // Set pixels from slice of [r,g,b] or [r,g,b,brightness], (colors=[][]uint8) → ()
		{255, 128, 0},   {128, 0, 255},   {0, 255, 128},
		{255, 255, 0},   {0, 255, 255},   {255, 0, 255},
		{128, 128, 128}, {255, 255, 255},
	})
	strip.Show()                                                 // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
	time.Sleep(500 * time.Millisecond)

	// set_pixels with per-pixel hardware brightness
	strip.SetPixels([][]uint8{                                    // Set pixels with varying hardware brightness, (colors=[][]uint8) → ()
		{255, 0, 0, 31}, {255, 0, 0, 16}, {255, 0, 0, 8},  {255, 0, 0, 4},
		{0, 255, 0, 31}, {0, 255, 0, 16}, {0, 255, 0, 8},  {0, 255, 0, 4},
	})
	strip.Show()                                                 // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
	time.Sleep(500 * time.Millisecond)

	// brightness — global software scale applied at show() time
	strip.SetBrightness(64)                                      // Set global software brightness, (value=0–255) → ()
																 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
	strip.Show()                                                 // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
	time.Sleep(500 * time.Millisecond)
	strip.SetBrightness(255)                                     // Set global software brightness, (value=0–255) → ()
																 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

	// fill_hsv — fill all pixels from HSV colour
	strip.FillHSV(0.0, 1.0, 1.0)                                 // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → error
																 // converts HSV to RGB then calls fill(); hue 0.0 = red
	time.Sleep(500 * time.Millisecond)
	strip.FillHSV(0.333, 1.0, 1.0)                               // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → error
																 // converts HSV to RGB then calls fill(); hue 0.333 = green
	time.Sleep(500 * time.Millisecond)
	strip.FillHSV(0.667, 1.0, 1.0)                               // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → error
																 // converts HSV to RGB then calls fill(); hue 0.667 = blue
	time.Sleep(500 * time.Millisecond)

	// rotate — shift pixel buffer left, then show
	strip.SetPixels([][]uint8{                                    // Set pixels from slice of [r,g,b], (colors=[][]uint8) → ()
		{255, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
		{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0},
	})
	strip.Show()                                                 // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
	time.Sleep(500 * time.Millisecond)
	for i := 0; i < 7; i++ {
		strip.Rotate(1)                                          // Rotate pixel buffer left, (steps=1) → ()
																 // shifts buffer by steps pixel positions; wraps around; does not send
		strip.Show()                                             // Transmit buffer to strip, () → error
																 // applies software brightness scaling then calls connection.Write()
		time.Sleep(200 * time.Millisecond)
	}

	strip.Off()                                                  // Turn off all pixels, () → error
																 // equivalent to Fill(0, 0, 0)
	time.Sleep(time.Second)
}