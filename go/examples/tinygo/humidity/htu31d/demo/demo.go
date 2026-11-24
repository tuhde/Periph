//go:build tinygo

// HTU31D demo — TinyGo / Raspberry Pi Pico W.
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

	conn := connection.NewI2CConnection(i2c, 0x40, nil, nil) // Create I2C connection, (i2c, addr=0x40, ) → (*I2CConnection)

	chip := humidity.NewHTU31DFull(conn) // Create HTU31D driver, (connection) → *HTU31DFull
	chk(chip.Init())                     // Soft reset, () → error

	// --- Identify the chip and check its health ---
	// The serial number is unique per device; the diagnostic byte reports NVM
	// and out-of-range errors.
	sn, err := chip.SerialNumber() // Read the serial number, () → (uint32, error)
	chk(err)
	diag, err := chip.Diagnostic() // Read the diagnostic byte, () → (byte, error)
	chk(err)
	fmt.Printf("serial 0x%06X diagnostic 0x%02X\n", sn, diag)

	// --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
	// The dew point shows how close the air is to condensing on a surface.
	for i := 0; i < 15; i++ {
		t, rh, err := chip.Read() // Read temperature and humidity, () → (float32 °C, float32 %RH, error)
		chk(err)
		dew, err := chip.DewPoint() // Compute dew point, () → (float32 °C, error)
		chk(err)
		fmt.Printf("%.2f C  %.1f %%RH  dew %.2f C\n", t, rh, dew)
		time.Sleep(2 * time.Second)
	}

	// --- Compare the four oversampling settings ---
	// Higher OSR gives finer resolution at the cost of a longer conversion.
	for osr := uint8(0); osr < 4; osr++ {
		chk(chip.SetResolution(osr, osr)) // Set oversampling, (osrRH 0-3, osrT 0-3) → error
		t, rh, err := chip.Read()
		chk(err)
		fmt.Printf("OSR %d typ %.2f ms: %.2f C %.1f %%RH\n", osr, chip.ConversionTime()*1000, t, rh)
	}
	chk(chip.SetResolution(3, 3))

	// --- Heater: drive off condensation for 10 s ---
	// Humidity falls and temperature rises while the heater runs; measure
	// before and after, never during, then let the sensor cool.
	t0, rh0, err := chip.Read()
	chk(err)
	chk(chip.SetHeater(true))    // Switch the heater, (on) → error
	on, err := chip.IsHeaterOn() // Heater state, () → (bool, error)
	chk(err)
	fmt.Println("heater on:", on)
	time.Sleep(10 * time.Second)
	chk(chip.SetHeater(false))
	t1, rh1, err := chip.Read()
	chk(err)
	fmt.Printf("before %.2f C %.1f %%RH, after %.2f C %.1f %%RH\n", t0, rh0, t1, rh1)

	// --- Non-blocking conversion ---
	// Start the conversion, do other work, then collect the result.
	chk(chip.StartConversion()) // Start a conversion, () → error
	time.Sleep(30 * time.Millisecond)
	t, rh, err := chip.ReadResult() // Read last conversion result, () → (float32 °C, float32 %RH, error)
	chk(err)
	fmt.Printf("result %.2f C %.1f %%RH\n", t, rh)

	// --- Reset ---
	chk(chip.SoftReset()) // Soft reset, () → error
}
