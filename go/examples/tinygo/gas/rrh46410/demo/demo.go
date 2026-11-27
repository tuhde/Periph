//go:build tinygo

// RRH46410 demo example — TinyGo / Raspberry Pi Pico W.
//
// Indoor air-quality monitor: identity, humidity compensation, IAQ readings with the
// warm-up progress, interrupt-driven samples, the other operation modes, GPIOs and reset.
package main

import (
	"fmt"
	"machine"
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
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x38, nil, nil) // Create I2C connection, (i2c, addr=0x38) → (*I2CConnection)

	chip, err := gas.NewRRH46410Full(conn) // Create RRH46410 driver, (connection) → (*RRH46410Full, error)
	chk(err)
	chk(chip.Init()) // Check product ID, start IAQ 2nd Gen mode, () → error

	// --- Identity ---
	// Get Product ID returns 0x2310 and the firmware version; the tracking number
	// identifies the individual module.
	pid, fw, err := chip.ProductID() // Product ID and firmware, () → (uint16, [3]uint8, error)
	chk(err)
	fmt.Printf("product 0x%04X, firmware %d.%d.%d\n", pid, fw[0], fw[1], fw[2])
	tn, err := chip.TrackingNumber() // Tracking number, () → ([]byte, error)
	chk(err)
	mode, err := chip.GetMode() // Operation mode, () → (uint8, error)
	chk(err)
	fmt.Printf("tracking %X, mode %d\n", tn, mode)

	// --- Humidity compensation ---
	// The MOx element reacts to humidity; feeding the real value improves the
	// results. The module assumes 50 %RH.
	chk(chip.SetHumidity(42)) // Ambient humidity, (percent %RH) → error

	// --- Indoor air-quality monitor ---
	// A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
	// warm-up during which the values stay fixed.
	warmup := chip.WarmupSamples(-1)                                                    // Warm-up length, (mode, -1 = current) → int samples
	fmt.Printf("sample period %v, warm-up %d samples\n", chip.SamplePeriod(-1), warmup) // Sample period, (mode, -1 = current) → time.Duration
	for i := 0; i < 20; i++ {
		r, err := chip.Read() // Next sample, () → (RRH46410Result, error)
		if err != nil {
			continue
		}
		progress := 100
		if !r.WarmedUp {
			progress = int(r.Sample) * 100 / warmup
		}
		fmt.Printf("IAQ %.1f (%s)  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  rel %d  warm-up %d%%\n",
			r.IAQ, chip.AirQualityLabel(r.IAQ), r.TVOCMgM3, r.EtOHPPM, r.ECO2PPM, r.RelativeIAQ, progress) // UBA rating, (iaq) → string
	}

	// --- Interrupt instead of polling ---
	// With INT wired the driver reacts to its rising edge; otherwise it polls the
	// sample counter. The callback receives the decoded result.
	chk(chip.OnInterrupt(func(r gas.RRH46410Result) { fmt.Printf("INT sample %d IAQ %.1f\n", r.Sample, r.IAQ) })) // Subscribe, (callback) → error
	time.Sleep(10 * time.Second)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	for {
		pending, err := chip.PollInterrupt() // New sample pending, () → (bool, error)
		if err != nil || pending {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if ok, _ := chip.WaitForSample(4 * time.Second); ok { // Wait for a new sample, (timeout) → (bool, error)
		fmt.Printf("latest %+v\n", chip.LastResult()) // Last decoded result, () → RRH46410Result
	} else if r, ok, _ := chip.ReadResults(); ok { // Latest result, () → (RRH46410Result, bool, error)
		fmt.Printf("latest %+v\n", r)
	}

	// --- Other operation modes ---
	// PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
	// one sample every 90 s. Each mode change restarts the warm-up.
	for _, m := range []struct {
		mode uint8
		name string
	}{{gas.RRH46410ModePBAQ, "PBAQ"}, {gas.RRH46410ModeULP, "ULP"}} {
		chk(chip.SetMode(m.mode))                                                                                      // Operation mode, (mode) → error
		fmt.Printf("%s: sample every %v, warm-up %d samples\n", m.name, chip.SamplePeriod(-1), chip.WarmupSamples(-1)) // Mode timing, (mode) → time.Duration / int
	}
	chk(chip.SetMode(gas.RRH46410ModePBAQ)) // Operation mode, (mode) → error
	r, err := chip.Read()                   // Next sample, () → (RRH46410Result, error)
	chk(err)
	fmt.Printf("PBAQ TVOC %.3f mg/m3 EtOH %.3f ppm\n", r.TVOCMgM3, r.EtOHPPM)

	// --- GPIOs ---
	// GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
	chk(chip.ConfigGPIO(0x03)) // Both outputs, (directions) → error
	for i := 0; i < 3; i++ {
		chk(chip.SetGPIO(0x01)) // GPIO1 high, (mask) → error
		time.Sleep(200 * time.Millisecond)
		chk(chip.ClearGPIO(0x01)) // GPIO1 low, (mask) → error
		time.Sleep(200 * time.Millisecond)
	}
	g, err := chip.GetGPIO() // GPIO levels, () → (uint8, error)
	chk(err)
	fmt.Printf("GPIO levels %04b, status %+v\n", g, chip.Status()) // Last status byte, () → RRH46410Status
	raw, err := chip.SendCommand(0x10, nil, 1)                     // Raw request, (command, params, responseLen) → ([]byte, error)
	chk(err)
	fmt.Println("raw mode", raw[0])

	// --- Wrap up ---
	// CleanSensor(true) runs the 1-minute cleaning once in the sensor's lifetime;
	// it is skipped here. Suspend stops measuring; Reset restarts the module in SUSPEND.
	chk(chip.Suspend())        // Suspend mode, () → error
	chk(chip.Reset())          // Reset, () → error
	mode, err = chip.GetMode() // Operation mode, () → (uint8, error)
	chk(err)
	fmt.Println("mode after reset", mode)
}
