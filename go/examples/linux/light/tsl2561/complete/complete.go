//go:build linux && !tinygo

// TSL2561 complete example — Linux host.
//
// Exercises every method in the public API: lux and raw counts, gain,
// integration time, package coefficients, auto-range, manual integration,
// the interrupt window and interrupt delivery, and power control.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)

	conn, err := connection.NewI2CConnection(bus, light.TSL2561I2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x39) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	sensor, err := light.NewTSL2561Full(conn) // Create TSL2561 Full driver, (conn) → (*TSL2561Full, error)
	// powers up, CONTROL read back, TIMING ← 0x02 (1×, 402 ms), waits 450 ms
	must(err)

	id, err := sensor.ReadID() // Read the ID register, () → (uint8, error)
	// bits 7:4 = 1 for the TSL2561, bits 3:0 revision
	must(err)
	fmt.Println("id", id)
	lx, err := sensor.ReadLux() // Read illuminance, () → (float32 lux, error)
	// *TSL2561SaturatedError on a saturated channel
	fmt.Println("lux", lx, err)
	ch0, ch1, err := sensor.ReadRaw() // Read both channels, () → (uint16 ch0, uint16 ch1, error)
	// one block read: ch0 visible+IR, ch1 IR
	must(err)
	lx, ch0, ch1, err = sensor.ReadLuxRaw() // Lux plus counts, () → (float32, uint16, uint16, error)
	fmt.Println("lux", lx, "ch0", ch0, "ch1", ch1)
	fmt.Println("calculated", light.TSL2561CalculateLux(ch0, ch1, 1, 402, "T")) // Convert counts, (ch0, ch1, gain, integrationMs, pkg) → float32 lux

	must(sensor.SetGain(16)) // Set ADC gain, (gain 1|16) → error
	// 16× for dim light; saturates indoors at ~1–2 klx
	must(sensor.SetIntegrationTime(101)) // Set integration time, (ms 14|101|402) → error
	// the conversion in progress keeps the old setting
	fmt.Println("gain", sensor.Gain(), "integration", sensor.IntegrationTime()) // Current settings, () → uint8, () → uint16 ms
	time.Sleep(250 * time.Millisecond)
	must(sensor.SetPackage("CS")) // Select lux coefficients, (pkg "T"|"FN"|"CL"|"CS") → error
	// T, FN and CL share one set; CS has its own
	lx, err = sensor.ReadLux()
	fmt.Println("lux CS", lx, err)
	must(sensor.SetPackage("T"))
	lx, err = sensor.AutoRange() // Auto-range and read lux, () → (float32 lux, error)
	// 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100
	fmt.Println("auto", lx, err)

	must(sensor.StartManual()) // Start a host-timed integration, () → error
	// INTEG = 11, MANUAL = 1
	time.Sleep(200 * time.Millisecond)
	must(sensor.StopManual()) // Stop the integration, () → error
	// INTEG = 11, MANUAL = 0; counts are ready
	ch0, ch1, _ = sensor.ReadRaw()
	fmt.Println("manual ch0", ch0, "ch1", ch1)
	must(sensor.SetIntegrationTime(402))
	must(sensor.SetGain(1))

	must(sensor.SetThresholds(100, 20000)) // Set channel-0 window, (low, high counts) → error
	// word writes of 0x2–0x3 and 0x4–0x5
	low, high, err := sensor.Thresholds() // Read the window, () → (uint16 low, uint16 high, error)
	must(err)
	fmt.Println("window", low, high)
	must(sensor.ConfigureInterrupt(1, 2)) // Interrupt mode, (mode 0|1|2, persist 0–15) → error
	// level interrupt after 2 cycles outside the window
	outside, err := sensor.PollInterrupt() // Check ch0 against the window, () → (bool, error)
	// does not clear the chip's interrupt
	must(err)
	fmt.Println("outside", outside)
	must(sensor.ClearInterrupt()) // Release a level interrupt, () → error
	// command byte 0xC0
	must(sensor.OnInterrupt(func(c0 uint16) { fmt.Println("interrupt", c0) })) // Subscribe, (callback(ch0)) → error
	// falling edge on INT, or poll once per integration
	time.Sleep(10 * time.Second)
	must(sensor.OffInterrupt()) // Unsubscribe, () → error
	must(sensor.ConfigureInterrupt(0, 0))

	on, err := sensor.IsPowered() // Power state, () → (bool, error)
	// CONTROL reads 0x03 when powered
	must(err)
	fmt.Println("powered", on)
	must(sensor.PowerDown()) // Power down, () → error
	// CONTROL = 0x00; registers keep their values
	must(sensor.PowerUp()) // Power up, () → error
	// CONTROL = 0x03; integration restarts
}
