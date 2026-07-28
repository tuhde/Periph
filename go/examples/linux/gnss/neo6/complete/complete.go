//go:build linux && !tinygo

// NEO-6 complete example — Linux host.
//
// Exercises every method in the NEO6Full API: position fields, RMC/VTG
// extensions (speed, course, UTC time/date), HDOP, UBX message
// framing, rate and platform configuration, cold start, and config
// persistence.
package main

import (
	"encoding/hex"
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/gnss"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x42"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x42) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip := gnss.NewNEO6Full(conn, "i2c") // Create NEO-6 driver, (connection, bus_type="i2c") → *NEO6Full

	// Minimal-inherited position fields (these populate on the first
	// GGA sentence with a valid fix status > 0).
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

	// Drive one update cycle so subsequent Full-only fields are
	// populated (requires a real sentence in the receive buffer).
	_, _ = chip.Update() // Read and parse one NMEA sentence, () → (bool, error)

	// Full-only fields (RMC/VTG, GGA hdop).
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

	// UBX framing helpers (class 0x06 CFG poll to read back what the
	// module is currently configured to output; ignored if no
	// sentence arrives within the response budget).
	chip.SendUBX(0x06, 0x01, []byte{0xF0, 0x00, 0x00}) // Send UBX message, (class=0x06, id=0x01, payload) → error
	// sends CFG-MSG (disable NMEA GGA on current port) with sync + Fletcher checksum

	payload, err := chip.PollUBX(0x06, 0x00) // Send UBX poll, (class=0x06, id=0x00) → ([]byte, error)
	// polls CFG-PRT; reads back the port configuration payload
	if err == nil {
		fmt.Printf("cfg_prt payload: %s\n", hex.EncodeToString(payload))
	} else {
		fmt.Printf("cfg_prt poll: %v\n", err)
	}

	// Configuration via CFG-RATE and CFG-NAV5.
	if err := chip.SetRate(1); err != nil { // Set navigation rate, (hz=1) → error
		// measRate=1000, navRate=1, timeRef=UTC
		fmt.Println("set_rate:", err)
	}
	if err := chip.SetPlatform(4); err != nil { // Set dynamic platform model, (model=4) → error
		// automotive dynModel
		fmt.Println("set_platform:", err)
	}

	// Reset and persist.
	if err := chip.ColdStart(); err != nil { // Force cold start, () → error
		// CFG-RST navBbrMask=0xFFFF, resetMode=0x02 (GNSS-only controlled reset)
		fmt.Println("cold_start:", err)
	}
	if err := chip.SaveConfig(); err != nil { // Persist configuration, () → error
		// CFG-CFG saveMask=0xFFFFFFFF to BBR|Flash|EEPROM
		fmt.Println("save_config:", err)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
