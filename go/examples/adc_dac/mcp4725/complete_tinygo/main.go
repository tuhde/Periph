//go:build tinygo

// MCP4725 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the MCP4725Full API on bare metal.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x60)             // Create I2C transport, (i2c, addr=0x60) → (*I2CTransport)
	chip, err := adcdac.NewMCP4725Full(tr)                 // Create MCP4725 driver, (transport) → (*MCP4725Full, error)
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

	if err := chip.SetPowerDown(1); err != nil { // Set power-down mode with code preserved, (mode=0–3) → error
		panic(err)
	}
	// 1 kΩ to GND power-down

	if err := chip.SetPowerDown(2); err != nil { // Set power-down mode with code preserved, (mode=0–3) → error
		panic(err)
	}
	// 100 kΩ to GND power-down

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
