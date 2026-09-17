//go:build linux && !tinygo

// MCP2515 demo — loopback heartbeat — Linux host.
//
// Configures the MCP2515 in Loopback mode (no physical CAN bus required).
// Sends a heartbeat frame with standard ID 0x001 and a 4-byte payload
// containing the uptime in seconds (big-endian) once per second. Also
// polls for received frames and prints each one's ID (hex), DLC, data
// (hex bytes), and frame type. In Loopback mode the sent heartbeat is
// received back immediately, demonstrating the full TX→RX round trip
// without external hardware.
package main

import (
	"encoding/binary"
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

	conn, err := connection.NewSPIConnection(bus, device, 10_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=10 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := comms.NewMCP2515Full(conn, uint16(bitrate), uint8(oscMhz)) // Create MCP2515 driver, (connection, bitrate_kbps=125, osc_mhz=8) → (*MCP2515Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for self-contained loopback test ---
	// Loopback mode routes transmitted frames straight into the RX buffers
	// without putting anything on the external CAN bus; perfect for
	// exercising the full TX→RX round trip on a bench.
	if err := chip.SetMode(comms.OPMODLoopback); err != nil { // Enter Loopback mode, (mode=OPMODLoopback=0x40) → error
		panic(err)
	}
	if err := chip.SetRxMode(0, 0x03); err != nil { // RXB0 accept-all, (buf=0, mode=3) → error
		panic(err)
	}
	if err := chip.SetRxMode(1, 0x03); err != nil { // RXB1 accept-all, (buf=1, mode=3) → error
		panic(err)
	}

	const heartbeatID = 0x001
	start := time.Now()

	for n := 0; n < 10; n++ {
		// --- Send the heartbeat with the current uptime (seconds, big-endian) ---
		uptime := uint32(time.Since(start).Seconds())
		payload := make([]byte, 4)
		binary.BigEndian.PutUint32(payload, uptime)
		if err := chip.Send(heartbeatID, payload, false); err != nil { // Send heartbeat, (id=0x001, data=[uptime_be32], extended=false) → error
			fmt.Fprintln(os.Stderr, "send:", err)
		} else {
			fmt.Printf("tx id=0x%03X data=%08X (uptime=%ds)\n", heartbeatID, uptime, uptime)
		}

		// --- Drain anything that came back (loopback echoes the heartbeat) ---
		for {
			frame, err := chip.Recv(20) // Drain received frames, (timeout_ms=20) → (*CanFrame, error)
			if err != nil {
				fmt.Fprintln(os.Stderr, "recv:", err)
				break
			}
			if frame == nil {
				break
			}
			kind := "std"
			if frame.Extended {
				kind = "ext"
			}
			if frame.RTR {
				kind = "rtr-" + kind
			}
			fmt.Printf("    rx id=0x%X dlc=%d %s data=", frame.ID, len(frame.Data), kind)
			for _, b := range frame.Data {
				fmt.Printf("%02X", b)
			}
			fmt.Println()
		}

		time.Sleep(1 * time.Second)
	}

	// --- Return the chip to Normal mode ---
	if err := chip.SetMode(comms.OPMODNormal); err != nil { // Return to Normal mode, (mode=OPMODNormal=0x00) → error
		panic(err)
	}
	fmt.Println("done — 10 heartbeats sent and received via loopback")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
