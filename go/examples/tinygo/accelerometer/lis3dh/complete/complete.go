//go:build tinygo

// LIS3DH complete example — TinyGo (Raspberry Pi Pico W).
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/accelerometer"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, 0x18, nil, nil) // Create I2C connection, (i2c, addr=0x18) → *I2CConnection
	defer conn.Close()

	chip, err := accelerometer.NewLIS3DHFull(conn, 0x18) // Create LIS3DH Full driver, (connection, addr=0x18) → (*LIS3DHFull, error)
	must(err)                                            // runs the init sequence: WHO_AM_I check, ±2 g, 100 Hz, high-resolution

	must(chip.SetRange(accelerometer.LIS3DHRange4G))   // Set measurement range, (rangeG=2|4|8|16) → error
	must(chip.SetDataRate(50))                         // Set output data rate, (rateHz=0|1|10|25|50|100|200|400|1344|1620|5376) → error
	must(chip.SetMode(accelerometer.LIS3DHModeNormal)) // Set operating mode, (mode=0 low-power|1 normal|2 high-resolution) → error
	must(chip.SetAxes(true, true, true))               // Enable measurement axes, (x, y, z) → error
	must(chip.PowerDown())                             // Stop the conversions, () → error

	rx, ry, rz, err := chip.ReadRaw()     // Read raw counts, () → (int, int, int, error)
	must(err)                             // signed counts at the current resolution
	ready, err := chip.NewDataAvailable() // Check for a new sample set, () → (bool, error)
	must(err)                             // STATUS_REG.ZYXDA
	status, err := chip.ReadStatus()      // Read the status register, () → (uint8, error)
	must(err)                             // overrun and data-available flags

	temp, err := chip.ReadTemperatureDelta() // Read the relative temperature, () → (int °C, error)
	must(err)                                // enables ADC_EN + TEMP_EN on first use
	volt, err := chip.ReadADC(1)             // Read an auxiliary ADC channel, (channel=1|2|3) → (float64 V, error)
	must(err)                                // 1.200 V centre, ±400 mV range
	must(chip.EnableADC(true))               // Enable the auxiliary ADC, (enabled) → error
	must(chip.EnableADC(false))              // Disable the auxiliary ADC, (enabled) → error

	must(chip.SetHighPass(1, 0, true, true, false, false)) // Configure the high-pass filter, (mode, cutoff, filterOutput, onIA1, onIA2, onClick) → error
	ref, err := chip.ResetHighPass()                       // Reset the high-pass filter, () → (uint8, error)
	must(err)                                              // reading REFERENCE resets the filter in HPM = 00
	must(chip.SetReference(0x10))                          // Set the high-pass reference, (value=0..255) → error

	must(chip.SetFIFO(2, 16, false))                   // Configure the FIFO, (mode=0|1|2|3, watermark=0..31, triggerInt2) → error
	level, err := chip.FIFOLevel()                     // Read the FIFO level, () → (int, error)
	must(err)                                          // FSS<4:0>, the number of unread sample sets
	samples, err := chip.ReadFIFO(0)                   // Drain the FIFO, (count=0 reads all) → ([]Sample, error)
	must(err)                                          // one 6-byte burst per sample set, oldest first
	wm, ovrn, empty, lvl, err := chip.ReadFIFOStatus() // Read the FIFO status, () → (watermark, overrun, empty bool, level int, error)
	must(err)                                          // the three decoded FIFO_SRC_REG flags

	must(chip.SetMotionInterrupt(1, 0x3F, 0.5, 10, true)) // Configure interrupt generator 1, (generator, events, thresholdG, durationMs, andMode) → error
	must(chip.SetFreeFall(1, 0.35, 60))                   // Configure free-fall, (generator, thresholdG, durationMs) → error
	must(chip.SetOrientation(1, false, false))            // Configure 6D/4D orientation, (generator, position, fourD) → error
	must(chip.SetLatch(1, true))                          // Latch the generator interrupt, (generator, enabled) → error
	src, err := chip.ReadInterruptSource(1)               // Read the generator status, (generator) → (uint8, error)
	must(err)                                             // reading clears the interrupt
	must(chip.SetClick(0, 0x04, 0.8, 20, 40, 200, true))  // Configure click detection, (single, double, thresholdG, timeLimitMs, latencyMs, windowMs, latch) → error
	click, err := chip.ReadClickSource()                  // Read the click status, () → (uint8, error)
	must(err)                                             // reading clears the click interrupt
	must(chip.SetActivity(0.1, 100))                      // Configure the activity function, (thresholdG, durationMs) → error
	must(chip.SetInterruptPolarity(true))                 // Set the INT pin polarity, (activeLow) → error

	must(chip.EnableInterrupt(accelerometer.SourceClick, 1))  // Route a source to INT1, (source, pin=1|2) → error
	must(chip.DisableInterrupt(accelerometer.SourceClick, 1)) // Unroute a source, (source, pin=1|2) → error
	must(chip.OnInterrupt(func(status uint16) {               // Subscribe to the INT line, (callback) → error
		fmt.Printf("interrupt status=0x%04X\n", status)
	})) // falls back to a polling goroutine when no INT pin is wired
	must(chip.OffInterrupt()) // Unsubscribe, () → error

	irq, err := chip.PollInterrupt() // Poll and clear the interrupt status, () → (uint16, error)
	must(err)                        // reading the source registers clears their latched flags
	must(chip.SetSDOPullup(true))    // Connect the SDO pull-up, (connected) → error
	st, err := chip.SelfTest(1)      // Run the self-test, (mode=1|2) → (bool, error)
	must(err)                        // every axis must show a 17-360 LSb change
	must(chip.Reboot())              // Reboot the memory content, () → error

	x, y, z, err := chip.Read() // Read acceleration, () → (float64 g, float64 g, float64 g, error)
	must(err)

	fmt.Printf("raw=(%d, %d, %d) ready=%v status=0x%02X temp=%d °C adc=%.4f V\n",
		rx, ry, rz, ready, status, temp, volt)
	fmt.Printf("reference=0x%02X fifo level=%d drained=%d wm=%v ovrn=%v empty=%v fifo_status_level=%d\n",
		ref, level, len(samples), wm, ovrn, empty, lvl)
	fmt.Printf("interrupt source=0x%02X click source=0x%02X irq=0x%04X self-test=%v\n",
		src, click, irq, st)
	fmt.Printf("xyz=(%.4f, %.4f, %.4f) g\n", x, y, z)
}
