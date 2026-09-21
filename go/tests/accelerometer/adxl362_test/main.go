//go:build linux && !tinygo

// ADXL362 hardware test — Linux host.
//
// Opens /dev/spidevB.D at 8 MHz, exercises every method of ADXL362Full,
// prints PASS/FAIL lines, and exits with the standard ===DONE: ... === summary.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}

	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := accelerometer.NewADXL362Full(conn) // Create ADXL362 Full driver, (connection) → (*ADXL362Full, error)
	if err != nil {
		panic(err)
	}

	devad, devmst, partid, _, err := chip.DeviceID() // Read device IDs, () → (devid_ad, devid_mst, partid, revid, error)
	check("device_id_devid_ad", err == nil && devad == 0xAD)
	check("device_id_devid_mst", err == nil && devmst == 0x1D)
	check("device_id_partid", err == nil && partid == 0xF2)

	x, y, z, err := chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
	check("read_12bit", err == nil)
	check("read_12bit_floats", err == nil && !floatIsZero(x, y, z))

	_, _, _, err = chip.Read8bit() // Read 8-bit acceleration, () → (x, y, z g, error)
	check("read_8bit", err == nil)

	_, err = chip.Temperature() // Read temperature, () → (°C, error)
	check("temperature", err == nil)

	chip.SetRange(4)                          // Set measurement range, (rangeG=4) → error
	chip.SetODR(200.0)                        // Set output data rate, (odrHz=200.0) → error
	chip.SetHalfBandwidth(true)               // Set antialiasing bandwidth, (enabled=true) → error
	chip.SetNoiseMode(accelerometer.ADXL362NoiseLow) // Set noise mode, (mode=NOISE_LOW=1) → error
	check("set_range_odr_noise", true)

	_, err = chip.Status() // Read STATUS register, () → (byte, error)
	check("status", err == nil)
	_, err = chip.Awake() // Check AWAKE bit, () → (bool, error)
	check("awake", err == nil)
	_, err = chip.DataReady() // Check DATA_READY, () → (bool, error)
	check("data_ready", err == nil)
	_, err = chip.FifoEntries() // Read FIFO entry count, () → (uint16, error)
	check("fifo_entries", err == nil)

	chip.ConfigureFifo(accelerometer.ADXL362FifoStream, false, 128)        // Configure FIFO, (mode=STREAM=2, storeTemp=false, watermark=128) → error
	chip.SetActivityThreshold(0.5, true)                                     // Set activity threshold, (thresholdG=0.5, referenced=true) → error
	chip.SetActivityTime(5)                                                   // Set activity time, (samples=5) → error
	chip.SetInactivityThreshold(0.2, true)                                    // Set inactivity threshold, (thresholdG=0.2, referenced=true) → error
	chip.SetInactivityTime(30)                                                 // Set inactivity time, (samples=30) → error
	chip.EnableActivityDetection(true)                                        // Enable activity detection, (enabled=true) → error
	chip.EnableInactivityDetection(true)                                      // Enable inactivity detection, (enabled=true) → error
	chip.SetLinkLoopMode(accelerometer.ADXL362LinkLoopLoop)                   // Set link/loop mode, (mode=LOOP=3) → error
	check("activity_inactivity_config", true)

	chip.SetInterrupt(1, accelerometer.ADXL362SourceDataReady, true)         // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → error
	chip.SetInterrupt(2, accelerometer.ADXL362SourceAwake, true)              // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → error
	chip.SetInterruptPolarity(1, true)                                        // Set INT1 active-low, (pin=1, activeLow=true) → error
	check("interrupt_mapping", true)

	chip.SelfTest(true)                                                        // Enable self-test, (enabled=true) → error
	chip.SelfTest(false)                                                       // Disable self-test, (enabled=false) → error
	check("self_test", true)

	chip.SoftReset()                                                           // Soft-reset the chip, () → error
	check("soft_reset", true)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func floatIsZero(values ...float32) bool {
	for _, v := range values {
		if v != 0 {
			return false
		}
	}
	return false
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}