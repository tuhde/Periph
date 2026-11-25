//go:build tinygo

// Si1132 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every Si1132Full method once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil) // Create I2C connection, (i2c, addr=0x60, ) → (*I2CConnection)

	chip := light.NewSi1132Full(conn) // Create Si1132 driver, (connection) → *Si1132Full
	chk(chip.Init())                  // Verify PART_ID and start forced mode, () → error

	uv, vis, ir, err := chip.Read() // Read UV index and ALS counts, () → (float32, uint16, uint16, error)
	// forced measurement of the enabled channels
	chk(err)
	fmt.Printf("UV %.2f, visible %d, infrared %d\n", uv, vis, ir)
	uv, err = chip.ReadUVIndex() // Read UV index, () → (float32, error)
	// forced measurement, AUX_DATA / 100
	chk(err)
	fmt.Printf("uv index %.2f\n", uv)
	vis, err = chip.ReadVisible() // Read visible counts, () → (uint16, error)
	chk(err)
	fmt.Println("visible", vis)
	ir, err = chip.ReadInfrared() // Read infrared counts, () → (uint16, error)
	chk(err)
	fmt.Println("infrared", ir)
	vis, ir, aux, err := chip.ReadRaw() // Read data registers, () → (visible, infrared, aux uint16, error)
	// latest values, no measurement is started
	chk(err)
	fmt.Println("raw", vis, ir, aux)
	aux, err = chip.ReadTemperatureRaw() // Read die temperature, () → (uint16, error)
	// auxiliary channel, relative only
	chk(err)
	fmt.Println("temperature raw", aux)
	aux, err = chip.ReadVddRaw() // Read supply voltage, () → (uint16, error)
	// auxiliary channel with AUX_ADCMUX = 0x75
	chk(err)
	fmt.Println("vdd raw", aux)

	chk(chip.SetChannels(true, true, true, false)) // Select channels, (uv, visible, infrared, aux bool) → error
	// writes CHLIST; uv and aux cannot be combined
	chk(chip.SetGain(2, 2)) // Set ADC gain, (visible 0–7, infrared 0–7) → error
	// integration time x 2^n plus the recommended recovery count
	chk(chip.SetGain(0, 0))
	chk(chip.SetHighRange(false, false)) // Set high signal range, (visible, infrared bool) → error
	// true divides the sensitivity by 14.5 (direct sunlight)
	chk(chip.SetAlignment(true, true)) // Set data alignment, (visibleLow, infraredLow bool) → error
	// true reports the least significant 16 bits
	chk(chip.SetUVCoefficients([4]byte{0x7B, 0x6B, 0x01, 0x00})) // Set UV coefficients, (ucoef [4]byte) → error
	// datasheet defaults for an open-air UV index
	cal, err := chip.ReadCalibration() // Read factory calibration, () → ([12]byte, error)
	// GET_CAL then registers 0x22–0x2D
	chk(err)
	fmt.Println("calibration", cal)

	chk(chip.Force()) // Start a forced measurement, () → error
	// ALS_FORCE without waiting
	time.Sleep(50 * time.Millisecond)
	ready, err := chip.MeasurementReady() // Check measurement done, () → (bool, error)
	// IRQ_STATUS.ALS_INT
	chk(err)
	fmt.Println("ready", ready)
	counter, code, err := chip.ReadResponse() // Read command response, () → (counter, error code uint8, error)
	// RESPONSE decoded, error code 0 = none
	chk(err)
	fmt.Println("response", counter, code)
	st, err := chip.PollInterrupt() // Poll interrupt status, () → (uint8, error)
	// reads IRQ_STATUS and clears it
	chk(err)
	fmt.Println("poll", st)

	chk(chip.StartAutonomous(0.5)) // Start autonomous mode, (intervalS 0–2.05) → error
	// MEAS_RATE in 31.25 µs steps then ALS_AUTO
	chk(chip.OnInterrupt(func(s uint8) { fmt.Printf("INT status 0x%02X\n", s) })) // Subscribe to measurements, (callback func(uint8)) → error
	// falling edge on INT, else polling
	time.Sleep(2 * time.Second)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	chk(chip.Pause())        // Pause autonomous mode, () → error
	// ALS_PAUSE
	chk(chip.SetI2CAddress(0x60)) // Set I²C address, (address 7-bit) → error
	// I2C_ADDR parameter then BUSADDR, volatile
	chk(chip.Reset()) // Reset the chip, () → error
	// RESET, waits, writes HW_KEY again
}
