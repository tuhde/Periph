//go:build tinygo

// MCP2515 complete example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=SDO, GP16=SDI, GP17=CS) and
// exercises every method in the MCP2515Full API: per-buffer transmit,
// receive, acceptance filter and mask configuration, RX buffer mode
// switching, operating-mode switching (including sleep and loopback),
// error counter readout, overflow clearing, TX abort, and one-shot mode.
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
	chip, err := comms.NewMCP2515Full(conn, 125, 8)                 // Create MCP2515 driver, (connection, bitrate_kbps=125, osc_mhz=8) → (*MCP2515Full, error)
	if err != nil {
		panic(err)
	}

	// --- Acceptance filter / mask configuration ---
	if err := chip.SetMask(0, 0x7FF, false); err != nil { // Configure acceptance mask 0, (mask_num=0, mask=0x7FF, extended=false) → error
		panic(err)
	}
	// mask=0x7FF: every standard ID bit must match the filter
	if err := chip.SetFilter(0, 0x100, false); err != nil { // Configure acceptance filter 0, (filter_num=0, id=0x100, extended=false) → error
		panic(err)
	}
	if err := chip.SetFilter(1, 0x200, false); err != nil { // Configure acceptance filter 1, (filter_num=1, id=0x200, extended=false) → error
		panic(err)
	}
	if err := chip.SetRxMode(0, 0x00); err != nil { // Set RXB0 to standard filter mode, (buf=0, mode=0) → error
		panic(err)
	}
	if err := chip.SetRxMode(1, 0x03); err != nil { // Set RXB1 to accept-all, (buf=1, mode=3) → error
		panic(err)
	}

	// --- Operating mode switching ---
	if err := chip.SetMode(comms.OPMODLoopback); err != nil { // Enter Loopback mode, (mode=OPMODLoopback=0x40) → error
		panic(err)
	}
	mode, err := chip.GetMode() // Read current operating mode, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	println("mode=", mode)

	// --- Transmit on TXB0 ---
	if err := chip.Send(0x100, []byte{0x01, 0x02, 0x03, 0x04}, false); err != nil { // Send CAN frame on TXB0, (id=0x100, data=[01 02 03 04], extended=false) → error
		panic(err)
	}
	if err := chip.SendBuffered(0x200, []byte{0xAA}, false, 1); err != nil { // Send CAN frame on TXB1, (id=0x200, data=[AA], extended=false, buf=1) → error
		panic(err)
	}
	if err := chip.SendBuffered(0x300, []byte{0xBB}, false, 2); err != nil { // Send CAN frame on TXB2, (id=0x300, data=[BB], extended=false, buf=2) → error
		panic(err)
	}

	// --- Receive (loopback echoes) ---
	frame, err := chip.Recv(100) // Receive one CAN frame, (timeout_ms=100) → (*CanFrame, error)
	if err != nil {
		panic(err)
	}
	if frame != nil {
		println("rx id=", frame.ID, "dlc=", len(frame.Data), "ext=", frame.Extended)
	}

	// --- Error counters ---
	tec, rec, eflg, err := chip.ReadErrors() // Read error counters, () → (tec uint8, rec uint8, eflg uint8, err error)
	if err != nil {
		panic(err)
	}
	println("tec=", tec, "rec=", rec, "eflg=", eflg)

	// --- Clear overflow flags ---
	if err := chip.ClearOverflow(0); err != nil { // Clear RX0OVR flag, (buf=0) → error
		panic(err)
	}
	if err := chip.ClearOverflow(1); err != nil { // Clear RX1OVR flag, (buf=1) → error
		panic(err)
	}

	// --- One-shot mode ---
	if err := chip.SetOneShot(true); err != nil { // Enable one-shot mode, (enable=true) → error
		panic(err)
	}
	if err := chip.SetOneShot(false); err != nil { // Disable one-shot mode, (enable=false) → error
		panic(err)
	}

	// --- TX abort ---
	if err := chip.AbortTx(); err != nil { // Abort all pending TX, () → error
		panic(err)
	}

	// --- Switch to Normal mode ---
	if err := chip.SetMode(comms.OPMODNormal); err != nil { // Enter Normal mode, (mode=OPMODNormal=0x00) → error
		panic(err)
	}

	// --- Sleep ---
	if err := chip.SetMode(comms.OPMODSleep); err != nil { // Enter Sleep mode, (mode=OPMODSleep=0x20) → error
		panic(err)
	}
	time.Sleep(100 * time.Millisecond)
	if err := chip.SetMode(comms.OPMODNormal); err != nil { // Wake to Normal mode, (mode=OPMODNormal=0x00) → error
		panic(err)
	}

	// --- SPI RESET ---
	if err := chip.Reset(); err != nil { // Issue SPI RESET, () → error
		panic(err)
	}
	// device returns to Configuration mode; re-init for subsequent activity
	if err := chip.Init(125, 8); err != nil { // Re-run init, (bitrate_kbps=125, osc_mhz=8) → error
		panic(err)
	}
}
