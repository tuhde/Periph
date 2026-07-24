//go:build tinygo

// NEO-6 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the NEO6Full API: position fields, RMC/VTG
// extensions (speed, course, UTC time/date), HDOP, UBX message
// framing, rate and platform configuration, cold start, and config
// persistence.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 100_000,
	}); err != nil {
		panic(err)
	}

	tr := transport.NewI2CTransport(i2c, 0x42)      // Create I2C transport, (i2c, addr=0x42) → *I2CTransport
	chip := gnss.NewNEO6Full(tr, "i2c")             // Create NEO-6 driver, (transport, bus_type="i2c") → *NEO6Full

	fmt.Printf("fix: %d\n", chip.Fix())          // Get last GGA fix quality, () → uint8
	fmt.Printf("sats: %d\n", chip.Satellites())  // Get last GGA satellite count, () → uint8
	lat := chip.Latitude()                       // Get last fix latitude, () → *float64
	lon := chip.Longitude()                      // Get last fix longitude, () → *float64
	alt := chip.Altitude()                       // Get last fix altitude, () → *float64
	if lat != nil {
		fmt.Printf("lat: %.6f\n", *lat)
	} else {
		fmt.Println("lat: <none>")
	}
	if lon != nil {
		fmt.Printf("lon: %.6f\n", *lon)
	} else {
		fmt.Println("lon: <none>")
	}
	if alt != nil {
		fmt.Printf("alt: %.1f m\n", *alt)
	} else {
		fmt.Println("alt: <none>")
	}

	_, _ = chip.Update() // Read and parse one NMEA sentence, () → (bool, error)

	speed := chip.Speed()        // Get speed over ground, () → *float64
	course := chip.Course()      // Get course over ground, () → *float64
	utcTime := chip.UTCTime()    // Get last UTC time, () → string
	utcDate := chip.UTCDate()    // Get last UTC date, () → string
	hdop := chip.HDOP()          // Get last HDOP, () → *float64
	if speed != nil {
		fmt.Printf("speed: %.2f m/s\n", *speed)
	} else {
		fmt.Println("speed: <none>")
	}
	if course != nil {
		fmt.Printf("course: %.2f deg\n", *course)
	} else {
		fmt.Println("course: <none>")
	}
	fmt.Printf("utc_time: %q\n", utcTime)
	fmt.Printf("utc_date: %q\n", utcDate)
	if hdop != nil {
		fmt.Printf("hdop: %.2f\n", *hdop)
	} else {
		fmt.Println("hdop: <none>")
	}

	if err := chip.SendUBX(0x06, 0x01, []byte{0xF0, 0x00, 0x00}); err != nil { // Send UBX message, (class=0x06, id=0x01, payload) → error
		// sends CFG-MSG (disable NMEA GGA on current port)
		fmt.Println("send_ubx:", err)
	}
	if _, err := chip.PollUBX(0x06, 0x00); err != nil { // Send UBX poll, (class=0x06, id=0x00) → ([]byte, error)
		// polls CFG-PRT
		fmt.Println("poll_ubx:", err)
	}

	if err := chip.SetRate(1); err != nil { // Set navigation rate, (hz=1) → error
		fmt.Println("set_rate:", err)
	}
	if err := chip.SetPlatform(4); err != nil { // Set dynamic platform model, (model=4) → error
		fmt.Println("set_platform:", err)
	}
	if err := chip.ColdStart(); err != nil { // Force cold start, () → error
		fmt.Println("cold_start:", err)
	}
	if err := chip.SaveConfig(); err != nil { // Persist configuration, () → error
		fmt.Println("save_config:", err)
	}

	// Cap the loop so the UF2 doesn't run forever; the example
	// exercises the API rather than the run-time behavior.
	time.Sleep(2 * time.Second)
}
