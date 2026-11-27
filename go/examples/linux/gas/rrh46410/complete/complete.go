//go:build linux && !tinygo

// RRH46410 complete example — Linux host.
//
// Calls every method of the Full driver once.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gas"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

var _ = fmt.Sprint
var _ = time.Second

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	chk(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x38"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x38) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip, err := gas.NewRRH46410Full(conn) // Create RRH46410 driver, (connection) → (*RRH46410Full, error)
	// Stores the connection only; no bus traffic yet. The module stretches the I²C clock.
	chk(err)
	chk(chip.Init()) // Check product ID, start IAQ 2nd Gen mode, () → error
	// Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

	pid, fw, err := chip.ProductID() // Product ID and firmware, () → (uint16, [3]uint8, error)
	// The product ID is always 0x2310.
	chk(err)
	fmt.Printf("product 0x%04X firmware %d.%d.%d\n", pid, fw[0], fw[1], fw[2])
	tn, err := chip.TrackingNumber() // ASIC tracking number, () → ([]byte, error)
	// Unique per module.
	chk(err)
	fmt.Printf("tracking %X\n", tn)
	mode, err := chip.GetMode() // Operation mode, () → (uint8, error)
	// 1 = IAQ 2nd Gen after init.
	chk(err)
	fmt.Println("mode", mode)

	chk(chip.SetHumidity(45)) // Ambient humidity, (percent %RH) → error
	// Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
	r, err := chip.Read() // Next sample, () → (RRH46410Result, error)
	// Waits for the sample counter to change; values are fixed during the warm-up.
	chk(err)
	fmt.Printf("%+v\n", r)
	ok, err := chip.WaitForSample(4 * time.Second) // Wait for a new sample, (timeout) → (bool, error)
	// True when the counter changed (or INT went high) within the timeout.
	chk(err)
	fmt.Println("new sample", ok)
	r, ok, err = chip.ReadResults() // Latest result, () → (RRH46410Result, bool, error)
	// Does not wait; ok is false before the first sample of the mode.
	chk(err)
	fmt.Printf("%v %+v\n", ok, r)
	fmt.Printf("%+v\n", chip.LastResult()) // Last decoded result, () → RRH46410Result
	// The sample stored by Read, ReadResults or WaitForSample.
	fmt.Println(chip.AirQualityLabel(2.4)) // UBA rating, (iaq) → string
	// "very good", "good", "medium", "poor" or "bad".
	fmt.Println(chip.SamplePeriod(-1), chip.WarmupSamples(-1)) // Mode timing, (mode, -1 = current) → time.Duration / int
	// 3 s and 100 samples in IAQ 2nd Gen mode.
	fmt.Printf("%+v\n", chip.Status()) // Last status byte, () → RRH46410Status
	// Error bits and the operation-mode error code (0xA = sensor damaged).

	chk(chip.OnInterrupt(func(r gas.RRH46410Result) { fmt.Println("sample", r.Sample, r.IAQ) })) // Subscribe, (callback) → error
	// Rising edge on INT, or a 200 ms poll comparing the sample counter.
	time.Sleep(7 * time.Second)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// Stops the pin handler or polling goroutine.
	ok, err = chip.PollInterrupt() // New sample pending, () → (bool, error)
	// INT level if wired, otherwise a counter comparison.
	chk(err)
	fmt.Println("pending", ok)

	chk(chip.SetMode(gas.RRH46410ModePBAQ)) // Operation mode, (mode) → error
	// PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
	r, err = chip.Read() // Next sample, () → (RRH46410Result, error)
	// PBAQ results have a finer resolution (raw / 1000); IAQ and eCO2 are 0.
	chk(err)
	fmt.Printf("%+v\n", r)
	chk(chip.SetMode(gas.RRH46410ModeULP)) // Operation mode, (mode) → error
	// ULP: one sample every 90 s at 0.23 mW.
	fmt.Println(chip.SamplePeriod(-1), chip.WarmupSamples(-1)) // Mode timing, (mode, -1 = current) → time.Duration / int
	// 90 s and 10 samples.

	chk(chip.ConfigGPIO(0x03)) // GPIO directions, (directions) → error
	// Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
	chk(chip.SetGPIO(0x01)) // Drive high, (mask) → error
	// GPIO1 high.
	g, err := chip.GetGPIO() // GPIO levels, () → (uint8, error)
	// Bits 0–3 = GPIO1–GPIO4.
	chk(err)
	fmt.Printf("gpio %04b\n", g)
	chk(chip.ClearGPIO(0x01)) // Drive low, (mask) → error
	// GPIO1 low.

	raw, err := chip.SendCommand(0x10, nil, 1) // Raw request, (command, params, responseLen) → ([]byte, error)
	// Checksums added and verified; here Get Operation Mode.
	chk(err)
	fmt.Println("raw mode", raw[0])
	// chk(chip.CleanSensor(true)) // One-time cleaning, (confirm) → error
	// Runs for one minute, once in the sensor's lifetime; left disabled here.
	chk(chip.Suspend()) // Suspend mode, () → error
	// Stops measuring, about 0.7 µA.
	chk(chip.Reset()) // Reset, () → error
	// Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
