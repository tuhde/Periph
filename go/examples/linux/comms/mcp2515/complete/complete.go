//go:build linux && !tinygo

// MCP2515 complete example — Linux host.
//
// Exercises every method in the MCP2515Full API: identity/configuration
// (Init), per-buffer transmit, receive, acceptance filter and mask
// configuration, RX buffer mode switching, operating-mode switching
// (including sleep and loopback), error counter readout, overflow
// clearing, TX abort, and one-shot mode.
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

	chip, err := comms.NewMCP2515Full(conn, uint16(bitrate), uint8(oscMhz)) // Create MCP2515 driver, (connection, bitrate_kbps=125, osc_mhz=8) → (*MCP2515Full, error)
	if err != nil {
		panic(err)
	}

	// --- Acceptance filter / mask configuration ---
	// Filter 0: accept standard ID 0x100 exactly. Filter 1: accept standard
	// ID 0x200 exactly. Mask 0 (RXB0) covers both, so RXB0 accepts either.
	if err := chip.SetMask(0, 0x7FF, false); err != nil { // Configure acceptance mask 0, (mask_num=0, mask=0x7FF, extended=false) → error
		panic(err)
	}
	// mask=0x7FF means every standard ID bit must match the filter
	if err := chip.SetFilter(0, 0x100, false); err != nil { // Configure acceptance filter 0, (filter_num=0, id=0x100, extended=false) → error
		panic(err)
	}
	// filter 0 → SIDH=0x20, SIDL=0x00
	if err := chip.SetFilter(1, 0x200, false); err != nil { // Configure acceptance filter 1, (filter_num=1, id=0x200, extended=false) → error
		panic(err)
	}
	// filter 1 → SIDH=0x40, SIDL=0x00
	if err := chip.SetRxMode(0, 0x00); err != nil { // Set RXB0 to standard filter mode, (buf=0, mode=0) → error
		panic(err)
	}
	// mode=0: standard filter match (RXRTR must match filter as well)
	if err := chip.SetRxMode(1, 0x03); err != nil { // Set RXB1 to accept-all, (buf=1, mode=3) → error
		panic(err)
	}
	// mode=3: accept all messages into RXB1 (bypasses filter/mask)

	// --- Operating mode switching ---
	if err := chip.SetMode(comms.OPMODLoopback); err != nil { // Enter Loopback mode, (mode=OPMODLoopback=0x40) → error
		panic(err)
	}
	// Loopback mode: TX frames are echoed back internally for testing without
	// external hardware
	mode, err := chip.GetMode() // Read current operating mode, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("mode=0x%02X\n", mode)

	// --- Transmit on TXB0 ---
	if err := chip.Send(0x100, []byte{0x01, 0x02, 0x03, 0x04}, false); err != nil { // Send CAN frame on TXB0, (id=0x100, data=[01 02 03 04], extended=false) → error
		panic(err)
	}
	// LOAD TX BUFFER 0 → RTS TXB0 → poll TXREQ cleared

	// --- Transmit on TXB1 and TXB2 explicitly ---
	if err := chip.SendBuffered(0x200, []byte{0xAA}, false, 1); err != nil { // Send CAN frame on TXB1, (id=0x200, data=[AA], extended=false, buf=1) → error
		panic(err)
	}
	// explicit buf=1 (TXB1)
	if err := chip.SendBuffered(0x300, []byte{0xBB}, false, 2); err != nil { // Send CAN frame on TXB2, (id=0x300, data=[BB], extended=false, buf=2) → error
		panic(err)
	}
	// explicit buf=2 (TXB2)

	// --- Receive (loopback echoes the sent frames into RXB0/RXB1) ---
	frame, err := chip.Recv(100) // Receive one CAN frame, (timeout_ms=100) → (*CanFrame, error)
	if err != nil {
		panic(err)
	}
	if frame != nil {
		fmt.Printf("rx id=0x%X dlc=%d data=%v ext=%t rtr=%t\n",
			frame.ID, len(frame.Data), frame.Data, frame.Extended, frame.RTR)
	}

	// --- Error counters ---
	tec, rec, eflg, err := chip.ReadErrors() // Read error counters, () → (tec uint8, rec uint8, eflg uint8, err error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("tec=%d rec=%d eflg=0x%02X\n", tec, rec, eflg)
	// tec = transmit error counter; rec = receive error counter; eflg = error flag bits

	// --- Clear overflow flags ---
	if err := chip.ClearOverflow(0); err != nil { // Clear RX0OVR flag, (buf=0) → error
		panic(err)
	}
	// RX0OVR cleared in EFLG via BIT MODIFY
	if err := chip.ClearOverflow(1); err != nil { // Clear RX1OVR flag, (buf=1) → error
		panic(err)
	}
	// RX1OVR cleared in EFLG via BIT MODIFY

	// --- One-shot mode ---
	if err := chip.SetOneShot(true); err != nil { // Enable one-shot mode, (enable=true) → error
		panic(err)
	}
	// OSM=1: no retransmit on error or loss of arbitration
	if err := chip.SetOneShot(false); err != nil { // Disable one-shot mode, (enable=false) → error
		panic(err)
	}
	// OSM=0: retransmit enabled (default)

	// --- TX abort ---
	if err := chip.AbortTx(); err != nil { // Abort all pending TX, () → error
		panic(err)
	}
	// sets ABAT in CANCTRL, polls until hardware clears it

	// --- Switch to Normal mode for actual bus operation ---
	if err := chip.SetMode(comms.OPMODNormal); err != nil { // Enter Normal mode, (mode=OPMODNormal=0x00) → error
		panic(err)
	}
	// polls CANSTAT until OPMOD=000

	// --- Sleep ---
	if err := chip.SetMode(comms.OPMODSleep); err != nil { // Enter Sleep mode, (mode=OPMODSleep=0x20) → error
		panic(err)
	}
	// Sleep: lowest power; wake on bus activity (if WAKIE/WAKFIL configured)
	time.Sleep(100 * time.Millisecond)
	if err := chip.SetMode(comms.OPMODNormal); err != nil { // Wake to Normal mode, (mode=OPMODNormal=0x00) → error
		panic(err)
	}

	// --- SPI RESET ---
	if err := chip.Reset(); err != nil { // Issue SPI RESET, () → error
		panic(err)
	}
	// device returns to Configuration mode; must call Init again to re-program

	// Re-init for any subsequent activity on the bus.
	if err := chip.Init(uint16(bitrate), uint8(oscMhz)); err != nil { // Re-run init, (bitrate_kbps=125, osc_mhz=8) → error
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
