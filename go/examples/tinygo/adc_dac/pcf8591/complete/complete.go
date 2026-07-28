//go:build tinygo

// PCF8591 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the PCF8591Full API on bare metal.
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
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x48, nil, nil)             // Create I2C connection, (i2c, addr=0x48) → (*I2CConnection)
	chip, err := adcdac.NewPCF8591Full(conn)                 // Create PCF8591 driver, (connection) → (*PCF8591Full, error)
	if err != nil {
		panic(err)
	}

	ch0, err := chip.ReadChannel(0) // Read single channel, (channel=0–3) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// discards the stale first conversion byte; returns 0–255

	ch1, err := chip.ReadChannel(1) // Read single channel, (channel=0–3) → (uint8, error)
	if err != nil {
		panic(err)
	}
	// selects channel 1 via the control byte, returns 0–255

	_, err = chip.ReadAll() // Read all four channels, () → ([4]uint8, error)
	if err != nil {
		panic(err)
	}
	// sets AI=1 and reads 5 bytes; discards stale byte 0

	v0, err := chip.ReadChannelVoltage(0, 3.3, 0.0) // Read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → (float32 V, error)
	if err != nil {
		panic(err)
	}
	// converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256

	_, err = chip.ReadAllVoltage(3.3, 0.0) // Read all channels as voltages, (vref=3.3 V, vagnd=0.0 V) → ([4]float32 V, error)
	if err != nil {
		panic(err)
	}
	// returns four voltages using the same conversion

	if err := chip.Configure(adcdac.PCF8591Mode3Differential, false, false); err != nil { // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → error
		panic(err)
	}
	// sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI

	diff, err := chip.ReadDifferential(0) // Read differential channel, (channel=0–2) → (int8, error)
	if err != nil {
		panic(err)
	}
	// returns signed 8-bit two's complement (-128 to 127)

	if err := chip.Configure(adcdac.PCF8591Mode4SingleEnded, false, true); err != nil { // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → error
		panic(err)
	}
	// restores 4 single-ended mode and enables the DAC output

	if err := chip.SetDAC(128); err != nil { // Enable DAC and set raw value, (value=0–255) → error
		panic(err)
	}
	// sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2

	if err := chip.SetDACVoltage(0.25); err != nil { // Set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → error
		panic(err)
	}
	// maps fraction to 0–255 and writes the DAC; AOUT follows

	if err := chip.DisableDAC(); err != nil { // Disable DAC output, () → error
		panic(err)
	}
	// clears AOE; AOUT returns to high-impedance

	fmt.Printf("ch0=%d ch1=%d v0=%.3fV diff=%d\n", ch0, ch1, v0, diff)
	time.Sleep(time.Second)
}
