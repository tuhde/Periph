//go:build linux && !tinygo

// Si1132 demo — Linux host.
//
// Calibrated hygrometer: dew point, output data rates, averaging noise, heater and DRDY.
package main

import (
	"fmt"
	"os"
	"strconv"
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
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x60"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x60) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := light.NewSi1132Full(conn) // Create Si1132 driver, (connection) → *Si1132Full
	chk(chip.Init())                  // Verify PART_ID and start forced mode, () → error

	// --- Identify the chip and show the factory calibration ---
	// The calibration bytes belong to the Silicon Labs Si114x calibration
	// structure; they let you correct part-to-part variation of the UV index.
	cal, err := chip.ReadCalibration() // Read factory calibration, () → ([12]byte, error)
	chk(err)
	fmt.Println("calibration", cal)

	// --- UV and light monitor: 20 readings, 1 s apart ---
	// Index, visible and infrared counts side by side show whether the
	// light is sunlight (high infrared share) or artificial.
	for i := 0; i < 20; i++ {
		uv, vis, ir, err := chip.Read() // Read UV index and ALS counts, () → (float32, uint16, uint16, error)
		chk(err)
		fmt.Printf("UV %.2f  vis %d  ir %d\n", uv, vis, ir)
		v, i2, aux, err := chip.ReadRaw() // Read data registers, () → (visible, infrared, aux uint16, error)
		chk(err)
		fmt.Println("raw", v, i2, aux)
		time.Sleep(time.Second)
	}

	// --- Effect of gain and of the high-range bits on the same light ---
	// Each gain step doubles the integration time (and the count); the high
	// range divides the sensitivity by 14.5 for direct sunlight.
	for _, gain := range []uint8{0, 2, 4} {
		chk(chip.SetGain(gain, gain)) // Set ADC gain, (visible 0–7, infrared 0–7) → error
		_, vis, ir, err := chip.Read()
		chk(err)
		fmt.Println("gain", gain, "visible", vis, "infrared", ir)
	}
	chk(chip.SetGain(0, 0))
	chk(chip.SetHighRange(true, true)) // Set high signal range, (visible, infrared bool) → error
	_, vis, ir, err := chip.Read()
	chk(err)
	fmt.Println("high range visible", vis, "infrared", ir)
	chk(chip.SetHighRange(false, false))
	chk(chip.SetAlignment(false, false))                         // Set data alignment, (visibleLow, infraredLow bool) → error
	chk(chip.SetUVCoefficients([4]byte{0x7B, 0x6B, 0x01, 0x00})) // Set UV coefficients, (ucoef [4]byte) → error

	// --- Auxiliary channels ---
	// The temperature value is relative only; the VDD count tracks the supply.
	temp, err := chip.ReadTemperatureRaw() // Read die temperature, () → (uint16, error)
	chk(err)
	fmt.Println("temperature raw", temp)
	vdd, err := chip.ReadVddRaw() // Read supply voltage, () → (uint16, error)
	chk(err)
	fmt.Println("vdd raw", vdd)

	// --- Autonomous mode at 0.5 s with the interrupt ---
	// Every completed measurement raises INT; the callback prints the status.
	chk(chip.StartAutonomous(0.5))                                         // Start autonomous mode, (intervalS 0–2.05) → error
	chk(chip.OnInterrupt(func(s uint8) { fmt.Printf("INT 0x%02X\n", s) })) // Subscribe to measurements, (callback func(uint8)) → error
	time.Sleep(5 * time.Second)
	chk(chip.OffInterrupt())              // Unsubscribe, () → error
	chk(chip.Pause())                     // Pause autonomous mode, () → error
	ready, err := chip.MeasurementReady() // Check measurement done, () → (bool, error)
	chk(err)
	counter, code, err := chip.ReadResponse() // Read command response, () → (counter, error code uint8, error)
	chk(err)
	fmt.Println("ready", ready, "response", counter, code)
	chk(chip.EnableInterrupt()) // Enable the INT pin, () → error
	chk(chip.Force())           // Start a forced measurement, () → error
	time.Sleep(50 * time.Millisecond)
	st, err := chip.PollInterrupt() // Poll interrupt status, () → (uint8, error)
	chk(err)
	fmt.Println("poll", st)
	chk(chip.DisableInterrupt()) // Disable the INT pin, () → error

	// --- UV only, then shut down ---
	// Dropping the ALS channels shortens each measurement.
	chk(chip.SetChannels(true, false, false, false)) // Select channels, (uv, visible, infrared, aux bool) → error
	uv, err := chip.ReadUVIndex()                    // Read UV index, () → (float32, error)
	chk(err)
	fmt.Printf("UV only %.2f\n", uv)
	chk(chip.SetI2CAddress(0x60)) // Set I²C address, (address 7-bit) → error
	chk(chip.Reset())             // Reset the chip, () → error
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
