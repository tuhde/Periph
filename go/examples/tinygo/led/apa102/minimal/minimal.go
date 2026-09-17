// APA102 minimal example for TinyGo.
// tinygo build -target=pico-w ./go/examples/tinygo/led/apa102/minimal/
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/led"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	// SPI0 on GP3 (MOSI/TX), GP2 (SCK), no MISO needed
	spi := machine.SPI0
	spi.Configure(machine.SPIConfig{
		Frequency: 1_000_000, // 1 MHz for APA102
		Mode:      0,
		SDO:       machine.GPIO3,  // MOSI
		SCK:       machine.GPIO2,  // SCK
	})

	conn := connection.NewSPIConnectionTinyGo(spi, nil, nil)

	strip, err := led.NewAPA102Minimal(conn, 30)
	if err != nil {
		panic(err)
	}

	for {
		strip.Fill(255, 0, 0)                                     // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → error
		time.Sleep(time.Second)
		strip.Fill(0, 255, 0)                                     // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → error
		time.Sleep(time.Second)
		strip.Fill(0, 0, 255)                                     // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → error
		time.Sleep(time.Second)
		strip.Off()                                               // Turn off all pixels, () → error
		time.Sleep(time.Second)
	}
}