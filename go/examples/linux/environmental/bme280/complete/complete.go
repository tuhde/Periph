//go:build linux && !tinygo

// BME280 complete example — Linux host.
//
// Exercises every method in the BME280Full API: configuration, status,
// chip ID, individual reads, altitude and dew-point helpers, and reset.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
	"github.com/tuhde/Periph/go/periph/connection"
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

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x76) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := environmental.NewBME280Full(conn) // Create BME280 driver, (connection) → (*BME280Full, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id=0x%02X\n", cid) // returns 0x60 for BME280

	chip.Configure( // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → error
		environmental.BME280OSRSX1,
		environmental.BME280OSRSX1,
		environmental.BME280OSRSX1,
		environmental.BME280ModeForced,
		environmental.BME280Filter4,
		environmental.BME280TSB125MS,
	) // writes ctrl_hum, config, ctrl_meas in correct order

	chip.SetOversampling( // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → error
		environmental.BME280OSRSX4,
		environmental.BME280OSRSX2,
		environmental.BME280OSRSX1,
	) // humidity update requires ctrl_meas write to latch
	chip.SetMode(environmental.BME280ModeForced)  // Set power mode, (mode 0/1/3) → error
	chip.SetFilter(environmental.BME280Filter4)   // Set IIR filter, (coeff 0–4) → error
	// suppresses short-term pressure disturbances
	chip.SetStandby(environmental.BME280TSB125MS) // Set standby time, (t_sb 0–7) → error
	// only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
	_ = chip

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
	h, err := chip.Humidity() // Read humidity, () → (float32 %RH, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.Altitude(1013.25) // Compute altitude, (sea_level_hpa=1013.25) → (float32 m, error)
	if err != nil {
		panic(err)
	}
	// uses barometric formula to convert pressure to metres
	slp, err := chip.SeaLevelPressure(alt) // Compute sea-level pressure, (altitude_m) → (float32 hPa, error)
	if err != nil {
		panic(err)
	}
	dp, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}
	// Magnus-Tetens approximation from current T and RH

	chip.Reset() // Soft reset chip, () → error
	// re-reads calibration and re-applies configuration

	fmt.Printf("T=%.1f C, P=%.1f hPa, RH=%.1f %%RH, alt=%.1f m, slp=%.1f hPa, dp=%.1f C\n",
		t, p, h, alt, slp, dp)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
