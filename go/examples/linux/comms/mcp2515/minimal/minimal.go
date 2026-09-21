//go:build linux && !tinygo

// MCP2515 minimal example — Linux host.
//
// Opens /dev/spidevB.D and configures an MCP2515 at 125 kbit/s with an
// 8 MHz oscillator. Sends a 4-byte "hello" frame on standard ID 0x123
// every 2 seconds, then polls for any received frame and prints it.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	bitrate, err := strconv.Atoi(envOr("MCP2515_BITRATE_KBPS", "125"))
	if err != nil {
		panic(err)
	}
	oscMhz, err := strconv.Atoi(envOr("MCP2515_OSC_MHZ", "8"))
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 10_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, mode=0, max_speed=10 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := comms.NewMCP2515Minimal(conn, uint16(bitrate), uint8(oscMhz)) // Create MCP2515 driver, (connection, bitrate_kbps=125, osc_mhz=8) → (*MCP2515Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		if err := chip.Send(0x123, []byte("hi"), false); err != nil { // Send CAN frame, (id=0x123, data=[104 105], extended=false) → error
			fmt.Fprintln(os.Stderr, "send:", err)
		} else {
			fmt.Println("sent id=0x123 data=hi")
		}

		frame, err := chip.Recv(10) // Receive one CAN frame, (timeout_ms=10) → (*CanFrame, error)
		if err != nil {
			fmt.Fprintln(os.Stderr, "recv:", err)
		}
		if frame != nil {
			fmt.Printf("rx id=0x%X dlc=%d data=%v ext=%t rtr=%t\n",
				frame.ID, len(frame.Data), frame.Data, frame.Extended, frame.RTR)
		}

		time.Sleep(2 * time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
