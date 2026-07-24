//go:build linux && !tinygo

// MCP4728 complete example — Linux host.
//
// Exercises every method in the MCP4728Full API: single-channel and
// set_all updates, EEPROM persistence, V_REF / gain / power-down
// configuration, read-back, Software Update, Wake-Up, Reset, and
// EEPROM-ready polling.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x60) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := adcdac.NewMCP4728Full(tr) // Create MCP4728 driver, (transport) → (*MCP4728Full, error)
	if err != nil {
		panic(err)
	}

	if err := chip.SetVoltage(0, 0.5); err != nil { // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → error
		panic(err)
	}
	// uses Multi-Write to update channel A's volatile DAC register

	if err := chip.SetRaw(1, 2048); err != nil { // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → error
		panic(err)
	}
	// clamps to [0, 4095] and writes channel B

	if err := chip.SetAll([4]float32{0.0, 0.25, 0.5, 1.0}); err != nil { // Update all four channels simultaneously, (fractions[4]) → error
		panic(err)
	}
	// single Fast Write transaction; PD=00, UDAC=0 for all four

	if err := chip.SetVoltageEEPROM(0, 0.5, 0, 1); err != nil { // Set output and persist to EEPROM, (channel, fraction, vref=0/1, gain=1/2) → error
		panic(err)
	}
	// Single Write (DAC + EEPROM) for channel A; V_REF=external, gain=1

	if err := chip.SetRawEEPROM(1, 2048, 0, 1); err != nil { // Set raw code and persist to EEPROM, (channel, code, vref, gain) → error
		panic(err)
	}
	// Single Write for channel B

	if err := chip.SetAllEEPROM( // Update all four channels and EEPROM, (fractions[4], vrefs[4], gains[4]) → error
		[4]float32{0.0, 0.25, 0.5, 0.75},
		[4]uint8{0, 0, 0, 0},
		[4]uint8{1, 1, 1, 1},
	); err != nil {
		panic(err)
	}
	// Sequential Write from A→D; waits for EEPROM write cycle

	if err := chip.SetVREF(0, 0, 0, 0); err != nil { // Set V_REF for all four channels, (vref_a, vref_b, vref_c, vref_d=0/1) → error
		panic(err)
	}
	// selects external V_DD reference for all channels

	if err := chip.SetGain(1, 1, 1, 1); err != nil { // Set gain for all four channels, (gain_a, gain_b, gain_c, gain_d=1/2) → error
		panic(err)
	}
	// gain=1 for all channels (gain=2 ignored when V_REF=external)

	if err := chip.SetPowerDown(0, 0, 0, 0); err != nil { // Set power-down mode for all four channels, (pd_a, pd_b, pd_c, pd_d=0–3) → error
		panic(err)
	}
	// all channels in Normal mode

	if err := chip.SetPowerDown(1, 2, 3, 0); err != nil { // Set power-down mode for all four channels, (pd_a, pd_b, pd_c, pd_d=0–3) → error
		panic(err)
	}
	// A=1k, B=100k, C=500k, D=Normal

	st, err := chip.Read() // Read all four channels, () → (MCP4728ReadResult, error)
	if err != nil {
		panic(err)
	}
	for i := 0; i < 4; i++ {
		fmt.Printf("ch%d code=%d vref=%d gain=%d pd=%d eeprom_code=%d eeprom_pd=%d\n",
			i, st.Channel[i].Code, st.Channel[i].VREF, st.Channel[i].Gain,
			st.Channel[i].PowerDown, st.Channel[i].EEPROMCode, st.Channel[i].EEPROMPowerDown)
	}

	if err := chip.SoftwareUpdate(); err != nil { // Send General Call Software Update to latch all V_OUT, () → error
		panic(err)
	}
	// sends 0x00, 0x08; updates all four channels simultaneously

	if err := chip.WakeUp(); err != nil { // Send General Call Wake-Up to clear all PD bits, () → error
		panic(err)
	}
	// sends 0x00, 0x09; clears power-down bits on all channels

	if err := chip.Reset(); err != nil { // Send General Call Reset to reload EEPROM, () → error
		panic(err)
	}
	// sends 0x00, 0x06; reloads EEPROM into all DAC registers

	ready, err := chip.IsEEPROMReady() // Check if EEPROM write is complete, () → (bool, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("eeprom_ready=%v\n", ready)
	// returns true when any pending EEPROM write has finished

	time.Sleep(time.Second)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
