//go:build tinygo

// BMP280 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the BMP280Full API on the Pico W: configuration,
// status, chip ID, individual reads, altitude and sea-level helpers, and reset.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	tr := transport.NewI2CTransport(i2c, 0x76)      // Create I2C transport, (i2c, addr=0x76) → (*I2CTransport)
	chip, err := pressure.NewBMP280Full(tr)        // Create BMP280 driver, (transport) → (*BMP280Full, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id=0x%02X\n", cid) // returns 0x58 for BMP280

	chip.Configure( // Configure chip, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → error
		pressure.BMP280OSRSX1,
		pressure.BMP280OSRSX1,
		pressure.BMP280ModeForced,
		pressure.BMP280FilterOff,
		pressure.BMP280TSB0p5MS,
	) // writes ctrl_meas and config registers

	chip.SetOversampling(pressure.BMP280OSRSX4, pressure.BMP280OSRSX2) // Set oversampling, (osrs_t 0–5, osrs_p 0–5) → error
	// changes conversion time vs resolution trade-off
	chip.SetMode(pressure.BMP280ModeForced) // Set power mode, (mode 0/1/3) → error
	chip.SetFilter(pressure.BMP280Filter4)   // Set IIR filter, (coeff 0–4) → error
	// suppresses short-term pressure disturbances
	chip.SetStandby(pressure.BMP280TSB125MS) // Set standby time, (t_sb 0–7) → error
	// only relevant in normal mode

	st, err := chip.Status() // Read status register, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("status=0x%02X\n", st)

	t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}
	p, err := chip.Pressure() // Read pressure, () → (float32 hPa, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.Altitude(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float32 m, error)
	if err != nil {
		panic(err)
	}
	// uses barometric formula to convert pressure to metres
	_, err = chip.SeaLevelPressure(alt) // Compute sea-level pressure, (altitude_m) → (float32 hPa, error)
	if err != nil {
		panic(err)
	}

	chip.Reset() // Soft reset chip, () → error
	// re-reads calibration and re-applies configuration

	fmt.Printf("T=%.1f C, P=%.1f hPa, alt=%.1f m\n", t, p, alt)
}
