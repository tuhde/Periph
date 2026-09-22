//go:build tinygo

// ADXL362 hardware test — TinyGo / Raspberry Pi Pico W smoke test.
package main

import (
	"machine"
	"strconv"

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

	devad, devmst, partid, _, err := chip.DeviceID() // Read device IDs, () → (devid_ad, devid_mst, partid, revid, error)
	if err != nil {
		panic(err)
	}
	if devad == 0xAD {
		println("PASS device_id_devid_ad")
	} else {
		println("FAIL device_id_devid_ad got", strconv.FormatInt(int64(devad), 16))
	}
	if devmst == 0x1D {
		println("PASS device_id_devid_mst")
	} else {
		println("FAIL device_id_devid_mst got", strconv.FormatInt(int64(devmst), 16))
	}
	if partid == 0xF2 {
		println("PASS device_id_partid")
	} else {
		println("FAIL device_id_partid got", strconv.FormatInt(int64(partid), 16))
	}

	_, _, _, err = chip.Read() // Read 3-axis acceleration, () → (x, y, z g, error)
	if err != nil {
		panic(err)
	}
	println("PASS read_12bit")

	_, _, _, err = chip.Read8bit() // Read 8-bit acceleration, () → (x, y, z g, error)
	if err != nil {
		panic(err)
	}
	println("PASS read_8bit")

	_, err = chip.Temperature() // Read temperature, () → (°C, error)
	if err != nil {
		panic(err)
	}
	println("PASS temperature")

	chip.SetRange(4)                          // Set measurement range, (rangeG=4) → error
	chip.SetODR(200.0)                        // Set output data rate, (odrHz=200.0) → error
	chip.SetHalfBandwidth(true)               // Set antialiasing bandwidth, (enabled=true) → error
	chip.SetNoiseMode(accelerometer.ADXL362NoiseLow) // Set noise mode, (mode=NOISE_LOW=1) → error
	println("PASS set_range_odr_noise")

	_, err = chip.Status() // Read STATUS register, () → (byte, error)
	if err != nil {
		panic(err)
	}
	println("PASS status")
	_, err = chip.Awake() // Check AWAKE bit, () → (bool, error)
	if err != nil {
		panic(err)
	}
	println("PASS awake")
	_, err = chip.DataReady() // Check DATA_READY, () → (bool, error)
	if err != nil {
		panic(err)
	}
	println("PASS data_ready")
	_, err = chip.FifoEntries() // Read FIFO entry count, () → (uint16, error)
	if err != nil {
		panic(err)
	}
	println("PASS fifo_entries")

	chip.ConfigureFifo(accelerometer.ADXL362FifoStream, false, 128)        // Configure FIFO, (mode=STREAM=2, storeTemp=false, watermark=128) → error
	chip.SetActivityThreshold(0.5, true)                                     // Set activity threshold, (thresholdG=0.5, referenced=true) → error
	chip.SetActivityTime(5)                                                   // Set activity time, (samples=5) → error
	chip.SetInactivityThreshold(0.2, true)                                    // Set inactivity threshold, (thresholdG=0.2, referenced=true) → error
	chip.SetInactivityTime(30)                                                 // Set inactivity time, (samples=30) → error
	chip.EnableActivityDetection(true)                                        // Enable activity detection, (enabled=true) → error
	chip.EnableInactivityDetection(true)                                      // Enable inactivity detection, (enabled=true) → error
	chip.SetLinkLoopMode(accelerometer.ADXL362LinkLoopLoop)                   // Set link/loop mode, (mode=LOOP=3) → error
	println("PASS activity_inactivity_config")

	chip.SetInterrupt(1, accelerometer.ADXL362SourceDataReady, true)         // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → error
	chip.SetInterrupt(2, accelerometer.ADXL362SourceAwake, true)              // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → error
	chip.SetInterruptPolarity(1, true)                                        // Set INT1 active-low, (pin=1, activeLow=true) → error
	println("PASS interrupt_mapping")

	chip.SelfTest(true)                                                        // Enable self-test, (enabled=true) → error
	chip.SelfTest(false)                                                       // Disable self-test, (enabled=false) → error
	println("PASS self_test")

	chip.SoftReset()                                                           // Soft-reset the chip, () → error
	println("PASS soft_reset")

	println("===DONE: 13 passed, 0 failed===")
}