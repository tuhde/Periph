//go:build tinygo

// SK6812RGBW minimal example — TinyGo / Raspberry Pi Pico W.
//
// Wires the SK6812RGBW DIN to GP22. Constructs the driver with
// 8 pixels and cycles through red, green, blue, white, and off.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	conn := connection.NewNeoPixelConnection(machine.GP22, nil) // Create NeoPixel connection, (pin=GP22) → (*NeoPixelConnection)
	strip, err := led.NewSK6812RGBWMinimal(conn, 8)       // Create SK6812RGBW driver, (connection, n=8) → (*SK6812RGBWMinimal, error)
	if err != nil {
		panic(err)
	}

	if err := strip.Fill(255, 0, 0, 0); err != nil { // Fill strip with red, (r=255, g=0, b=0, w=0) → error
		fmt.Printf("fill red: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 255, 0, 0); err != nil { // Fill strip with green, (r=0, g=255, b=0, w=0) → error
		fmt.Printf("fill green: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 0, 255, 0); err != nil { // Fill strip with blue, (r=0, g=0, b=255, w=0) → error
		fmt.Printf("fill blue: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Fill(0, 0, 0, 255); err != nil { // Fill strip with white, (r=0, g=0, b=0, w=255) → error
		fmt.Printf("fill white: %v\n", err)
	}
	time.Sleep(time.Second)
	if err := strip.Off(); err != nil { // Turn off strip, () → error
		fmt.Printf("off: %v\n", err)
	}
}
