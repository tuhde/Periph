//go:build linux && !tinygo

// BME680 complete example — Linux host.
//
// Exercises every method in the BME680Full API: configuration, multi-
// profile heater, gas enable, status, chip ID, and a single-shot read of
// all four sensors.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x76"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x76) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	chip, err := environmental.NewBME680Full(tr) // Create BME680 driver, (transport) → (*BME680Full, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id=0x%02X\n", cid) // returns 0x61 for BME680

	chip.Configure( // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1, filter 0–7) → error
		environmental.BME680OSRSX1,
		environmental.BME680OSRSX1,
		environmental.BME680OSRSX1,
		environmental.BME680ModeSleep,
		environmental.BME680Filter0,
	) // writes ctrl_hum, config, ctrl_meas in correct order

	chip.SetOversampling( // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → error
		environmental.BME680OSRSX4,
		environmental.BME680OSRSX2,
		environmental.BME680OSRSX1,
	) // changes conversion time vs resolution trade-off
	chip.SetFilter(environmental.BME680Filter7) // Set IIR filter, (coeff 0–7) → error
	// applies to temperature and pressure only
	chip.SetHeater(320, 150) // Configure heater profile 0, (temp_c, duration_ms) → error
	// sets target temperature and on-time for gas measurement
	chip.SetHeaterProfile(1, 200, 100) // Configure heater profile 1, (index 0–9, temp_c, duration_ms) → error
	chip.SelectHeaterProfile(0)        // Select active profile, (index 0–9) → error
	chip.SetGasEnabled(true)           // Enable gas conversion, (enabled) → error
	chip.SetHeaterOff(false)           // Control heater override, (off) → error
	chip.SetAmbientTemperature(25.0)   // Override ambient for heater calc, (temp_c) → error
	// re-applies the active heater profile
	_ = chip

	st, err := chip.Status() // Read status register, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("status=0x%02X\n", st)

	t, p, h, g, err := chip.ReadAll() // Read all sensors in one cycle, () → (float32 °C, float32 hPa, float32 %RH, float32 Ω, error)
	if err != nil {
		panic(err)
	}
	// returns (T, P, RH, R_gas) from single TPHG trigger
	gv, err := chip.GasValid() // Check gas validity, () → (bool, error)
	if err != nil {
		panic(err)
	}
	hs, err := chip.HeaterStable() // Check heater stability, () → (bool, error)
	if err != nil {
		panic(err)
	}

	chip.Reset() // Soft reset chip, () → error
	// re-reads calibration and re-applies configuration

	fmt.Printf("T=%.1f C, P=%.1f hPa, RH=%.1f %%, R_gas=%.0f Ohm (valid=%v, stable=%v)\n",
		t, p, h, g, gv, hs)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
