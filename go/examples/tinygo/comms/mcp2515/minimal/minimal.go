//go:build tinygo

// MCP2515 minimal example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=SDO, GP16=SDI, GP17=CS) and
// drives an MCP2515 at 125 kbit/s with an 8 MHz oscillator. Sends a
// 4-byte "hello" frame on standard ID 0x123 every 2 seconds and polls
// for any received frame.
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
		Frequency: 10_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	chip, err := comms.NewMCP2515Minimal(conn, 125, 8)              // Create MCP2515 driver, (connection, bitrate_kbps=125, osc_mhz=8) → (*MCP2515Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := chip.Send(0x123, []byte("hi"), false); err != nil { // Send CAN frame, (id=0x123, data=[104 105], extended=false) → error
			println("send:", err.Error())
		} else {
			println("sent id=0x123 data=hi")
		}

		frame, err := chip.Recv(10) // Receive one CAN frame, (timeout_ms=10) → (*CanFrame, error)
		if err != nil {
			println("recv:", err.Error())
		}
		if frame != nil {
			println("rx id=", frame.ID, "ext=", frame.Extended, "rtr=", frame.RTR, "dlc=", len(frame.Data))
		}

		time.Sleep(2 * time.Second)
	}
}
