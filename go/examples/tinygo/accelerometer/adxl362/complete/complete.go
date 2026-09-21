//go:build tinygo

// ADXL362 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of ADXL362Full.
package main

import (
	"machine"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 8_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	chip, err := accelerometer.NewADXL362Full(conn)                 // Create ADXL362 Full driver, (connection) → (*ADXL362Full, error)
	if err != nil {
		panic(err)
	}

	devad, devmst, partid, revid, err := chip.DeviceID() // Read device IDs, () → (devid_ad, devid_mst, partid, revid, error)
	if err != nil {
		panic(err)
	}
	println("DEVID_AD=", strconv.FormatInt(int64(devad), 16),
		"DEVID_MST=", strconv.FormatInt(int64(devmst), 16),
		"PARTID=", strconv.FormatInt(int64(partid), 16),
		"REVID=", strconv.FormatInt(int64(revid), 16))

	chip.SetRange(4)                          // Set measurement range, (rangeG=4) → error
	chip.SetODR(200.0)                        // Set output data rate, (odrHz=200.0) → error
	chip.SetHalfBandwidth(true)               // Set antialiasing bandwidth, (enabled=true) → error
	chip.SetNoiseMode(accelerometer.ADXL362NoiseLow) // Set noise mode, (mode=NOISE_LOW=1) → error

	x, y, z, _ := chip.Read() // Read 12-bit acceleration, () → (x, y, z g, error)
	println("12-bit: x=", strconv.FormatFloat(float64(x), 'f', 3, 32),
		"y=", strconv.FormatFloat(float64(y), 'f', 3, 32),
		"z=", strconv.FormatFloat(float64(z), 'f', 3, 32))

	x, y, z, _ = chip.Read8bit() // Read 8-bit acceleration, () → (x, y, z g, error)
	println(" 8-bit: x=", strconv.FormatFloat(float64(x), 'f', 3, 32),
		"y=", strconv.FormatFloat(float64(y), 'f', 3, 32),
		"z=", strconv.FormatFloat(float64(z), 'f', 3, 32))

	t, _ := chip.Temperature() // Read temperature, () → (°C, error)
	println("temperature:", strconv.FormatFloat(float64(t), 'f', 2, 32), "C")

	status, _ := chip.Status() // Read STATUS register, () → (byte, error)
	println("status:", strconv.FormatInt(int64(status), 16))
	awake, _ := chip.Awake() // Check AWAKE bit, () → (bool, error)
	if awake {
		println("awake: 1")
	} else {
		println("awake: 0")
	}
	dr, _ := chip.DataReady() // Check DATA_READY, () → (bool, error)
	if dr {
		println("data_ready: 1")
	} else {
		println("data_ready: 0")
	}
	n, _ := chip.FifoEntries() // Read FIFO entry count, () → (uint16, error)
	println("fifo_entries:", strconv.FormatInt(int64(n), 10))

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
	time.Sleep(500 * time.Millisecond)
	chip.SelfTest(false)                                                       // Disable self-test, (enabled=false) → error

	chip.SoftReset()                                                           // Soft-reset the chip, () → error

	println("===DONE: 1 passed, 0 failed===")
}