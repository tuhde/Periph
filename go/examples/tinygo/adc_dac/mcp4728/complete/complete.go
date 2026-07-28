//go:build tinygo

// MCP4728 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the MCP4728Full API on bare metal.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil)             // Create I2C connection, (i2c, addr=0x60) → (*I2CConnection)
	chip, err := adcdac.NewMCP4728Full(conn)                 // Create MCP4728 driver, (connection) → (*MCP4728Full, error)
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
