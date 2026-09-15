//go:build linux && !tinygo

// BMP581 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x46"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x46) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := pressure.NewBMP581Full(conn, false) // Create BMP581 driver, (connection) → (*BMP581Full, error)
	if err != nil {
		panic(err)
	}

	cid, err := chip.ChipID() // Read chip ID, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("chip_id=0x%02x (expect 0x50)\n", cid)

	err = chip.Configure(0x1C, pressure.BMP581OSR1X, pressure.BMP581OSR1X, true) // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en) → error
	if err != nil {
		panic(err)
	}
	err = chip.SetMode(pressure.BMP581ModeNormal) // Set power mode, (mode 0/1/2/3) → error
	if err != nil {
		panic(err)
	}
	err = chip.SetIIRFilter(pressure.BMP581IIRCoeff3, pressure.BMP581IIRBypass) // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → error
	if err != nil {
		panic(err)
	}
	err = chip.ConfigureFIFO(pressure.BMP581FIFOBoth, pressure.BMP581FIFOStream, 8) // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → error
	if err != nil {
		panic(err)
	}
	n, err := chip.FIFOCount() // Read FIFO frame count, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	err = chip.EnableDRDYInterrupt(true) // Enable data-ready interrupt, (enable) → error
	if err != nil {
		panic(err)
	}
	drdy, err := chip.DataReady() // Check data ready, () → (bool, error)
	if err != nil {
		panic(err)
	}
	pf, tf, err := chip.Forced() // Trigger FORCED measurement, () → (Pa, °C, error)
	if err != nil {
		panic(err)
	}
	pp, tc, err := chip.Both() // Read both atomically, () → (Pa, °C, error)
	if err != nil {
		panic(err)
	}
	alt, err := chip.Altitude(101325.0) // Compute altitude, (sea_level_pa=101325.0) → (float32, error)
	if err != nil {
		panic(err)
	}
	st, err := chip.Status() // Read STATUS, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	ist, err := chip.InterruptStatus() // Read INT_STATUS, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	op, ot, err := chip.EffectiveOSR() // Read effective OSR, () → (uint8, uint8, error)
	if err != nil {
		panic(err)
	}
	err = chip.SetOORThreshold(110000.0, 200.0, 1) // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → error
	if err != nil {
		panic(err)
	}
	err = chip.SoftwareReset() // Soft reset chip, () → error
	if err != nil {
		panic(err)
	}

	fmt.Printf("P=%.1f Pa  T=%.2f C  alt=%.1f m  frames=%d  drdy=%v  eff=(%d,%d)  status=0x%02x  isr=0x%02x\n",
		pp, tc, alt, n, drdy, op, ot, st, ist)
	fmt.Printf("forced: P=%.1f Pa  T=%.2f C\n", pf, tf)
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}