//go:build tinygo

// DS3502 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the public API: wiper by code, voltage and ratio,
// resistances, increment, MODE, EEPROM storage, acknowledge polling and endpoints.
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.DS3502I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x28) → *I2CConnection
	defer conn.Close()

	pot, err := adcdac.NewDS3502Full(conn, 15.0, 0.0) // Create DS3502 Full driver, (conn, vHigh=1.0 V, vLow=0.0 V) → (*DS3502Full, error)
	must(err)
	// writes CR = 0x80 (MODE = 1, SRAM-only) and caches WR

	must(pot.SetWiper(100)) // Set wiper position, (code 0–127) → error
	// writes WR only; the EEPROM is not touched
	w, err := pot.GetWiper() // Read wiper position, () → (uint8 0–127, error)
	must(err)
	// reads WR; always the live wiper, never the IVR
	println("wiper", w)
	must(pot.SetVoltage(6.0)) // Set RW output voltage, (volts V) → error
	// nearest code of V = V_RL + (V_RH − V_RL)·code/127
	v, err := pot.GetVoltage() // Read unloaded RW voltage, () → (float32 V, error)
	must(err)
	println("voltage", v)

	must(pot.SetRatio(0.25)) // Set divider ratio, (ratio 0.0–1.0) → error
	// code = round(ratio × 127)
	ratio, err := pot.GetRatio() // Read divider ratio, () → (float32, error)
	must(err)
	println("ratio", ratio)
	rl, err := pot.GetResistance('L') // Read wiper-to-RL resistance, (terminal 'L'|'H') → (float32 Ω, error)
	must(err)
	// nominal, RTOTAL = 10 kΩ ±20 %
	rh, err := pot.GetResistance('H') // Read wiper-to-RH resistance, (terminal) → (float32 Ω, error)
	must(err)
	println("R_WL", rl, "R_WH", rh)
	n, err := pot.Increment(5) // Step the wiper, (steps int) → (uint8 0–127, error)
	must(err)
	// read, add, clamp, write; returns the new code
	println("incremented to", n)

	eeprom, err := pot.GetMode() // Read MODE, () → (bool, error)
	must(err)
	// true if writes to 0x00 also program the EEPROM
	println("eeprom mode", eeprom)
	must(pot.SetMode(false)) // Select SRAM-only writes, (eeprom bool) → error
	// CR = 0x80; true would write CR = 0x00

	must(pot.StoreWiper(64)) // Store power-up position, (code int, negative = current) → error
	// MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
	must(pot.WaitReady(30)) // Acknowledge polling, (timeoutMs int) → error
	// returns ErrDS3502Timeout if the chip keeps NACKing

	pot.SetEndpoints(12.0, 2.0) // Change RH / RL voltages, (vHigh V, vLow V)
	// affects the conversions only; no I²C traffic
}
