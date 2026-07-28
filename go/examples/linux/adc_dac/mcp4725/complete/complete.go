//go:build linux && !tinygo

// MCP4725 complete example — Linux host.
//
// Exercises every method in the MCP4725Full API: voltage and raw writes,
// EEPROM persistence, read-back, all four power-down modes, Wake-Up,
// Reset, and EEPROM-ready polling.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewMCP4725Full(conn) // Create MCP4725 driver, (connection) → (*MCP4725Full, error)
	if err != nil {
		panic(err)
	}

	if err := chip.SetVoltage(0.75); err != nil { // Set output as fraction of V_DD, (fraction=0.0–1.0) → error
		panic(err)
	}
	// converts fraction to 12-bit code and issues Fast Write

	if err := chip.SetRaw(3000); err != nil { // Set raw 12-bit code, (code=0–4095) → error
		panic(err)
	}
	// clamps to [0, 4095] and writes DAC register only

	if err := chip.SetVoltageEEPROM(0.5); err != nil { // Set output and persist to EEPROM, (fraction=0.0–1.0) → error
		panic(err)
	}
	// writes both DAC register and EEPROM for power-cycle persistence

	if err := chip.SetRawEEPROM(2048); err != nil { // Set raw code and persist to EEPROM, (code=0–4095) → error
		panic(err)
	}
	// writes both DAC register and EEPROM for power-cycle persistence

	st, err := chip.Read() // Read DAC and EEPROM registers, () → (MCP4725State, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("code=%d vf=%.4f pd=%d eeprom_code=%d eeprom_pd=%d ready=%v\n",
		st.Code, st.VoltageFraction, st.PowerDown, st.EEPROMCode, st.EEPROMPowerDown, st.EEPROMReady)
	// returns code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready

	if err := chip.SetPowerDown(0); err != nil { // Set power-down mode with code preserved, (mode=0–3) → error
		panic(err)
	}
	// enters Normal mode; output stage drives the DAC code

	if err := chip.SetPowerDown(1); err != nil { // Set power-down mode with code preserved, (mode=0–3) → error
		panic(err)
	}
	// 1 kΩ to GND power-down

	if err := chip.SetPowerDown(2); err != nil { // Set power-down mode with code preserved, (mode=0–3) → error
		panic(err)
	}
	// 100 kΩ to GND power-down

	if err := chip.SetPowerDown(3); err != nil { // Set power-down mode with code preserved, (mode=0–3) → error
		panic(err)
	}
	// 500 kΩ to GND power-down

	if err := chip.WakeUp(); err != nil { // Send General Call Wake-Up to clear power-down, () → error
		panic(err)
	}
	// sends 0x00, 0x09 to address 0x00; clears PD bits in DAC register

	if err := chip.Reset(); err != nil { // Send General Call Reset and reload EEPROM, () → error
		panic(err)
	}
	// sends 0x00, 0x06; triggers internal POR and reloads DAC from EEPROM

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
