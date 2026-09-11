//go:build tinygo

// RFM95W minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 on the Pico W (GP18=SCK, GP19=SDO, GP16=SDI,
// GP17=CS), constructs the driver at 868 MHz, and sends a "hello" packet
// every 2 seconds.
package main

import (
	"time"

	"machine"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 5_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	radio, err := comms.NewRFM95Minimal(conn, 868_000_000)           // Create RFM95W driver, (connection, frequency_hz=868e6) → (*RFM95Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := radio.Send([]byte("hello")); err != nil { // Send packet, (data=bytes ≤255 B) → error
			println("send:", err.Error())
			time.Sleep(2 * time.Second)
			continue
		}
		println("sent; sleeping 2 s")
		time.Sleep(2 * time.Second)
	}
}
