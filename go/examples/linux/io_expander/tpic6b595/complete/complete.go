//go:build linux && !tinygo

// TPIC6B595 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	serIn, err := strconv.Atoi(envOr("SIPO_SER_IN", "19"))
	if err != nil {
		panic(err)
	}
	srck, err := strconv.Atoi(envOr("SIPO_SRCK", "26"))
	if err != nil {
		panic(err)
	}
	rc, err := strconv.Atoi(envOr("SIPO_RCK", "5"))
	if err != nil {
		panic(err)
	}
	sr, err := strconv.Atoi(envOr("SIPO_SRCLR", "6"))
	if err != nil {
		panic(err)
	}
	g, err := strconv.Atoi(envOr("SIPO_G", "13"))
	if err != nil {
		panic(err)
	}

	// --- TPIC6B595Minimal ---
	conn1, err := connection.NewSIPOSoftwareSPI(serIn, srck, rc, sr, g, nil) // Create SiPo connection, (serIn=19, srck=26, rck=5, srclr=6, g=13, enPin=nil) → (*SIPOConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn1.Close()

	chip1, err := ioexpander.NewTPIC6B595Minimal(conn1, 1) // Create TPIC6B595 minimal driver, (connection, numDevices=1) → (*TPIC6B595Minimal, error)
	if err != nil {
		panic(err)
	}
	// initialises every output to OFF (shadow zeroed, latched once)

	p0 := chip1.Pin(0) // Get pin proxy, (n=0) → TPIC6B595Pin
	if err := p0.Set(true); err != nil { // Set DMOS output ON, (high=true) → error
		panic(err)
	}
	// sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
	if err := p0.Set(false); err != nil { // Set DMOS output OFF, (high=false) → error
		panic(err)
	}
	// clears shadow[0] bit 0, retransmits and latches
	if err := p0.Toggle(); err != nil { // Invert shadow bit, () → error
		panic(err)
	}

	high, err := p0.Get() // Read shadow bit, () → (bool, error)
	if err != nil {
		panic(err)
	}
	// returns the shadow bit (no bus read — SiPo is write-only)
	fmt.Printf("p0 high=%v\n", high)

	if err := chip1.Fill(true); err != nil { // Set every output ON, (value=true) → error
		panic(err)
	}
	// fills every shadow byte with 0xFF and retransmits — fast "all on" path
	if err := chip1.Fill(false); err != nil { // Set every output OFF, (value=false) → error
		panic(err)
	}
	// fills every shadow byte with 0x00 and retransmits — fast "all off" path
	if err := chip1.Off(); err != nil { // Turn every output off, () → error
		panic(err)
	}
	// shorthand for Fill(false); the safe initial state

	if err := chip1.WritePort(0, 0xA5); err != nil { // Write port 0, (port=0, mask=0xA5) → error
		panic(err)
	}
	// sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF

	// --- TPIC6B595Full ---
	conn2, err := connection.NewSIPOSoftwareSPI(serIn, srck, rc, sr, g, nil) // Create SiPo connection, (serIn=19, srck=26, rck=5, srclr=6, g=13, enPin=nil) → (*SIPOConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn2.Close()

	chip2, err := ioexpander.NewTPIC6B595Full(conn2, 2) // Create TPIC6B595 full driver, (connection, numDevices=2) → (*TPIC6B595Full, error)
	if err != nil {
		panic(err)
	}
	// two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

	if err := chip2.WriteAll([]uint8{0x01, 0x80}); err != nil { // Write all device bytes, (values=[0x01, 0x80]) → error
		panic(err)
	}
	// updates both shadow bytes and performs one transmit + latch

	if err := chip2.Clear(); err != nil { // Pulse SRCLR, () → error
		panic(err)
	}
	// clears the shift register only; outputs unaffected until next RCK pulse
	if err := chip2.SetOutputEnable(false); err != nil { // Force every output off via G, (enabled=false) → error
		panic(err)
	}
	// drives G HIGH, blanking outputs without disturbing the shadow register
	if err := chip2.SetOutputEnable(true); err != nil { // Re-enable outputs, (enabled=true) → error
		panic(err)
	}
	// drives G LOW; outputs resume from the previously-latched state
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
