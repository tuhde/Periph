//go:build tinygo

// SPL06 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
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

	conn := connection.NewI2CConnection(i2c, 0x77, nil, nil) // Create I2C connection, (i2c, addr=0x77) → (*I2CConnection)

	chip, err := pressure.NewSPL06Full(conn) // Create SPL06 driver, (connection) → (*SPL06Full, error)
	// Stores the connection only; no bus traffic yet.
	chk(err)
	chk(chip.Init()) // Check ID, read coefficients, apply defaults, () → error
	// ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature.

	fmt.Printf("%+v\n", chip.Coefficients()) // Decoded calibration, () → SPL06Coefficients
	// The nine factory coefficients used by every compensation.
	m, err := chip.Read() // Temperature and pressure, () → (SPL06Measurement, error)
	// Command mode: one temperature, then one pressure measurement.
	chk(err)
	fmt.Printf("%.2f C %.2f Pa\n", m.TemperatureC, m.PressurePa)
	p, err := chip.ReadPressure() // Pressure, () → (float64 Pa, error)
	// Measures temperature first, because the compensation needs it.
	chk(err)
	t, err := chip.ReadTemperature() // Temperature, () → (float64 °C, error)
	// One temperature measurement only.
	chk(err)
	fmt.Printf("%.2f Pa %.2f C\n", p, t)
	praw, traw, err := chip.ReadRaw() // Raw results, () → (int32 praw, int32 traw, error)
	// Signed 24-bit values from the result registers.
	chk(err)
	c, err := chip.Compensate(praw, traw, 16, 1) // Compensate raw values, (praw, traw, pOversampling, tOversampling) → (SPL06Measurement, error)
	// Pure computation with the stored coefficients; no bus access.
	chk(err)
	fmt.Printf("raw %d %d -> %+v\n", praw, traw, c)
	alt, err := chip.Altitude(101325) // Altitude, (seaLevelPa) → (float64 m, error)
	// International barometric formula against the given sea-level pressure.
	chk(err)
	fmt.Printf("%.2f m\n", alt)

	chk(chip.SetPressureConfig(64, 4)) // Pressure oversampling and rate, (oversampling, rate) → error
	// 64x oversampling, 4 background readings per second; sets P_SHIFT.
	chk(chip.SetTemperatureConfig(2, 4)) // Temperature oversampling and rate, (oversampling, rate) → error
	// 2x oversampling, 4 per second; always keeps the external sensor.
	chk(chip.StartContinuous(true, true)) // Background mode, (pressure, temperature) → error
	// MEAS_CTRL = 111: the chip measures both on its own.
	m, err = chip.ReadContinuous() // Latest background result, () → (SPL06Measurement, error)
	// Waits for both ready flags; does not start a measurement.
	chk(err)
	fmt.Printf("continuous %+v\n", m)
	pr, tr, err := chip.DataReady() // Ready flags, () → (bool pressureReady, bool temperatureReady, error)
	// MEAS_CFG.PRS_RDY and TMP_RDY.
	chk(err)
	fmt.Println("ready", pr, tr)
	chk(chip.Stop()) // Standby, () → error
	// MEAS_CTRL = 000.

	chk(chip.EnableFIFO()) // FIFO on, () → error
	// Background results now queue in the 32-entry FIFO.
	chk(chip.StartContinuous(true, true)) // Background mode, (pressure, temperature) → error
	// Fill the FIFO for a second.
	time.Sleep(time.Second)
	chk(chip.Stop()) // Standby, () → error
	// Stop adding entries.
	empty, full, err := chip.FIFOStatus() // FIFO state, () → (bool empty, bool full, error)
	// FIFO_STS flags.
	chk(err)
	fmt.Println("fifo", empty, full)
	entries, err := chip.ReadFIFO() // Drain FIFO, () → ([]SPL06FifoEntry {Kind, Value}, error)
	// Kind "pressure" (Pa) or "temperature" (°C), in measured order.
	chk(err)
	fmt.Printf("%d FIFO entries %+v\n", len(entries), entries)
	chk(chip.FlushFIFO()) // Flush FIFO, () → error
	// Discards any remaining entries.
	chk(chip.DisableFIFO()) // FIFO off, () → error
	// Results go to the result registers again.

	chk(chip.EnableInterrupt(pressure.SPL06SourcePressure)) // Enable interrupt source, (source) → error
	// Pressure-ready interrupt on SDO (active low).
	st, err := chip.PollInterrupt() // Read and clear INT_STS, () → (uint8, error)
	// Bitmask of SPL06SourcePressure / SPL06SourceTemperature / SPL06SourceFIFOFull.
	chk(err)
	fmt.Printf("INT_STS 0x%02X\n", st)
	chk(chip.OnInterrupt(func(status uint8) { fmt.Printf("interrupt 0x%02X\n", status) })) // Subscribe, (callback) → error
	// Uses the connection's INT pin, or a 5 ms polling goroutine without one.
	_, err = chip.Read() // Trigger a measurement, () → (SPL06Measurement, error)
	// The pressure result raises the interrupt.
	chk(err)
	time.Sleep(100 * time.Millisecond)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// Stops delivery.
	chk(chip.DisableInterrupt(pressure.SPL06SourcePressure)) // Disable interrupt source, (source) → error
	// Clears CFG_REG.INT_PRS.
	chk(chip.Reset()) // Soft reset, () → error
	// RESET = 0x09, re-reads the coefficients and restores the init defaults.
}
