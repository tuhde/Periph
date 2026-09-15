//go:build tinygo

// MCP2515 demo — loopback heartbeat — TinyGo / Raspberry Pi Pico W.
//
// Hardware: MCP2515 wired to SPI0 (GP18=SCK, GP19=SDO, GP16=SDI,
// GP17=CS). The MCP2515 is configured in Loopback mode so transmitted
// frames are echoed back into the RX buffers without a physical CAN
// bus, demonstrating the full TX→RX round trip on a bare Pico W.
//
// Every second for 10 iterations: send a standard-ID 0x001 heartbeat
// with a 4-byte big-endian uptime counter as payload; then drain any
// received frames and print each one's ID, DLC, data, and frame type.
package main

import (
	"encoding/binary"
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
	chip, err := comms.NewMCP2515Full(conn, 125, 8)                 // Create MCP2515 driver, (connection, bitrate_kbps=125, osc_mhz=8) → (*MCP2515Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for self-contained loopback test ---
	// Loopback mode routes transmitted frames straight into the RX buffers
	// without putting anything on the external CAN bus; perfect for
	// exercising the full TX→RX round trip on a bare Pico W with no
	// external CAN hardware.
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
			println("send:", err.Error())
		} else {
			println("tx id=", heartbeatID, "uptime=", uptime)
		}

		// --- Drain anything that came back (loopback echoes the heartbeat) ---
		for {
			frame, err := chip.Recv(20) // Drain received frames, (timeout_ms=20) → (*CanFrame, error)
			if err != nil {
				println("recv:", err.Error())
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
			println("    rx id=", frame.ID, "dlc=", len(frame.Data), "kind=", kind, "data=", frame.Data)
		}

		time.Sleep(1 * time.Second)
	}

	// --- Return the chip to Normal mode ---
	if err := chip.SetMode(comms.OPMODNormal); err != nil { // Return to Normal mode, (mode=OPMODNormal=0x00) → error
		panic(err)
	}
	println("done — 10 heartbeats sent and received via loopback")
}
