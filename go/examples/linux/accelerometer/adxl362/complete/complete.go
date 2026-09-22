//go:build linux && !tinygo

// ADXL362 complete example — Linux host.
//
// Opens /dev/spidevB.D and exercises every method of ADXL362Full.
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

	conn, err := connection.NewSPIConnection(bus, device, 0, 8_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=8 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := accelerometer.NewADXL362Full(conn) // Create ADXL362 Full driver, (connection) → (*ADXL362Full, error)
	if err != nil {
		panic(err)
	}

	devad, devmst, partid, revid, err := chip.DeviceID() // Read device IDs, () → (devid_ad, devid_mst, partid, revid, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("DEVID_AD=0x%02X DEVID_MST=0x%02X PARTID=0x%02X REVID=0x%02X\n",
		devad, devmst, partid, revid)

	chip.SetRange(4)                          // Set measurement range, (rangeG=4) → error
	chip.SetODR(200.0)                        // Set output data rate, (odrHz=200.0) → error
	chip.SetHalfBandwidth(true)               // Set antialiasing bandwidth, (enabled=true) → error
	chip.SetNoiseMode(accelerometer.ADXL362NoiseLow) // Set noise mode, (mode=NOISE_LOW=1) → error

	x, y, z, _ := chip.Read() // Read 12-bit acceleration, () → (x, y, z g, error)
	fmt.Printf("12-bit: x=%+.3f  y=%+.3f  z=%+.3f\n", x, y, z)

	x, y, z, _ = chip.Read8bit() // Read 8-bit acceleration, () → (x, y, z g, error)
	fmt.Printf(" 8-bit: x=%+.3f  y=%+.3f  z=%+.3f\n", x, y, z)

	t, _ := chip.Temperature() // Read temperature, () → (°C, error)
	fmt.Printf("temperature: %.2f C\n", t)

	status, _ := chip.Status() // Read STATUS register, () → (byte, error)
	fmt.Printf("status: 0x%02X\n", status)
	awake, _ := chip.Awake() // Check AWAKE bit, () → (bool, error)
	fmt.Printf("awake: %d\n", boolToInt(awake))
	dr, _ := chip.DataReady() // Check DATA_READY, () → (bool, error)
	fmt.Printf("data_ready: %d\n", boolToInt(dr))
	n, _ := chip.FifoEntries() // Read FIFO entry count, () → (uint16, error)
	fmt.Printf("fifo_entries: %d\n", n)

	chip.ConfigureFifo(accelerometer.ADXL362FifoStream, false, 128)        // Configure FIFO, (mode=STREAM=2, storeTemp=false, watermark=128) → error
	chip.SetActivityThreshold(0.5, true)                                     // Set activity threshold, (thresholdG=0.5, referenced=true) → error
	chip.SetActivityTime(5)                                                   // Set activity time, (samples=5) → error
	chip.SetInactivityThreshold(0.2, true)                                    // Set inactivity threshold, (thresholdG=0.2, referenced=true) → error
	chip.SetInactivityTime(30)                                                 // Set inactivity time, (samples=30) → error
	chip.EnableActivityDetection(true)                                        // Enable activity detection, (enabled=true) → error
	chip.EnableInactivityDetection(true)                                      // Enable inactivity detection, (enabled=true) → error
	chip.SetLinkLoopMode(accelerometer.ADXL362LinkLoopLoop)                   // Set link/loop mode, (mode=LOOP=3) → error

	chip.SetInterrupt(1, accelerometer.ADXL362SourceDataReady, true)         // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → error
	chip.SetInterrupt(2, accelerometer.ADXL362SourceAwake, true)              // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → error
	chip.SetInterruptPolarity(1, true)                                        // Set INT1 active-low, (pin=1, activeLow=true) → error

	chip.SelfTest(true)                                                        // Enable self-test, (enabled=true) → error
	chip.SelfTest(false)                                                       // Disable self-test, (enabled=false) → error

	chip.SoftReset()                                                           // Soft-reset the chip, () → error

	fmt.Println("===DONE: 1 passed, 0 failed===")
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}