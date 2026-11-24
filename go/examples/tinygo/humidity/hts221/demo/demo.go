//go:build tinygo

// HTS221 demo — TinyGo / Raspberry Pi Pico W.
//
// Calibrated hygrometer: dew point, output data rates, averaging noise, heater and DRDY.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/humidity"
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

	conn := connection.NewI2CConnection(i2c, 0x5F, nil, nil) // Create I2C connection, (i2c, addr=0x5F, ) → (*I2CConnection)

	chip := humidity.NewHTS221Full(conn) // Create HTS221 driver, (connection) → *HTS221Full
	chk(chip.Init())                     // Read calibration and start one-shot mode, () → error

	// --- Identify the chip and show the factory calibration ---
	// Every HTS221 stores its own two-point calibration; Init read it and
	// every conversion uses it.
	id, err := chip.WhoAmI() // Read WHO_AM_I, () → (uint8, error)
	chk(err)
	fmt.Printf("who am i 0x%02X\n", id)
	fmt.Printf("calibration %+v\n", chip.ReadCalibration()) // Read calibration, () → HTS221Calibration

	// --- One-shot hygrometer: 10 readings, 2 s apart ---
	// Each Read starts a conversion; the dew point shows what the pair means
	// for condensation risk.
	for i := 0; i < 10; i++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %rH, error)
		chk(err)
		dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%.2f C  %.1f %%rH  dew %.2f C\n", t, rh, dew)
		rawH, err := chip.ReadRawHumidity() // Read raw humidity, () → (int16, error)
		chk(err)
		rawT, err := chip.ReadRawTemperature() // Read raw temperature, () → (int16, error)
		chk(err)
		fmt.Println("raw", rawH, rawT)
		h, err := chip.ReadHumidity() // Read humidity only, () → (float32 %rH, error)
		chk(err)
		tc, err := chip.ReadTemperature() // Read temperature only, () → (float32 °C, error)
		chk(err)
		fmt.Printf("h %.1f t %.2f\n", h, tc)
		time.Sleep(2 * time.Second)
	}

	// --- Continuous sampling at 1 Hz and 12.5 Hz ---
	// DataReady paces the loop; the sample count per second follows the rate.
	chk(chip.SetBlockDataUpdate(true)) // Set block data update, (enabled) → error
	for _, hz := range []float32{1, 12.5} {
		chk(chip.SetOutputDataRate(hz)) // Set output data rate, (hz 0|1|7|12.5) → error
		got, err := chip.GetOutputDataRate() // Get output data rate, () → (float32 Hz, error)
		chk(err)
		fmt.Println("rate", got, "Hz")
		count := 0
		end := time.Now().Add(3 * time.Second)
		for time.Now().Before(end) {
			ready, err := chip.DataReady() // Check data available, () → (bool, error)
			chk(err)
			if ready {
				_, _, err = chip.ReadContinuous() // Read latest sample, () → (float32, float32, error)
				chk(err)
				count++
			}
			time.Sleep(5 * time.Millisecond)
		}
		fmt.Printf("%.1f samples/s\n", float32(count)/3)
	}
	chk(chip.SetOutputDataRate(0)) // Set output data rate, (hz 0|1|7|12.5) → error

	// --- Noise at lowest and highest averaging ---
	// Ten one-shot samples each; the spread of the raw humidity shrinks as the
	// averaging grows.
	for _, a := range [][2]int{{2, 4}, {256, 512}} {
		chk(chip.SetAveraging(a[0], a[1])) // Set averaging, (temperatureSamples, humiditySamples) → error
		ta, ha, err := chip.GetAveraging() // Get averaging, () → (int, int, error)
		chk(err)
		fmt.Println("averaging", ta, ha)
		lo, hi := int16(32767), int16(-32768)
		for i := 0; i < 10; i++ {
			raw, err := chip.ReadRawHumidity() // Read raw humidity, () → (int16, error)
			chk(err)
			if raw < lo {
				lo = raw
			}
			if raw > hi {
				hi = raw
			}
		}
		fmt.Println("raw humidity spread", int(hi)-int(lo))
	}
	chk(chip.SetAveraging(16, 32)) // Set averaging, (temperatureSamples, humiditySamples) → error

	// --- Heater: drive off condensation for 10 s ---
	// Humidity falls and temperature rises while the heater runs; measure
	// before and after, never during.
	bt, brh, err := chip.Read() // Read temperature and humidity, () → (float32, float32, error)
	chk(err)
	chk(chip.SetHeater(true)) // Switch the heater, (on) → error
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on:", on)
	time.Sleep(10 * time.Second)
	chk(chip.SetHeater(false)) // Switch the heater, (on) → error
	at, arh, err := chip.Read() // Read temperature and humidity, () → (float32, float32, error)
	chk(err)
	fmt.Printf("before %.2f C %.1f %%rH, after %.2f C %.1f %%rH\n", bt, brh, at, arh)

	// --- Data-ready notification for 5 s ---
	// Uses the DRDY pin when the connection has one, otherwise a polling goroutine.
	chk(chip.ConfigureDRDY(true, true, false)) // Configure DRDY pin, (enabled, activeHigh, openDrain) → error
	chk(chip.SetOutputDataRate(1))             // Set output data rate, (hz 0|1|7|12.5) → error
	chk(chip.OnInterrupt(func(s uint8) { fmt.Printf("DRDY status 0x%02X\n", s) })) // Subscribe to data-ready, (callback func(uint8)) → error
	time.Sleep(5 * time.Second)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	ps, err := chip.PollInterrupt() // Poll interrupt status, () → (uint8, error)
	chk(err)
	st, err := chip.Status() // Read STATUS_REG, () → (uint8, error)
	chk(err)
	fmt.Printf("poll 0x%02X status 0x%02X\n", ps, st)
	chk(chip.SetOutputDataRate(0))

	// --- Power cycle and reload ---
	chk(chip.PowerDown()) // Power down, () → error
	chk(chip.PowerUp())   // Power up, () → error
	chk(chip.Reboot())    // Reload trimming, () → error
	t, _, err := chip.Read()
	chk(err)
	fmt.Printf("done: %.2f C\n", t)
}
