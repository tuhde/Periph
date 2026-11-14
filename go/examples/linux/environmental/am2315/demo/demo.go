//go:build linux && !tinygo

// AM2315 demo example — Linux host.
//
// Weather-station logger: a sample every 5 s with dew point and min/max tracking,
// every 10th sample as a real-time value, then the user registers as a counter.
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/environmental"
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
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x5C"), 0, 8)
	chk(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x5C) → (*I2CConnection, error)
	chk(err)
	defer conn.Close()

	chip := environmental.NewAM2315Full(conn)        // Create AM2315 driver, (connection) → *AM2315Full
	model, version, id, err := chip.ReadDeviceInfo() // Read device information, () → (uint16, byte, uint32, error)
	chk(err)
	fmt.Printf("model %d, version %d, device id 0x%08X\n", model, version, id)

	// --- Weather-station logger: a sample every 5 s, min/max tracking ---
	// The sensor measures after a communication and returns that result on the next one,
	// so every sample is the previous measurement; the driver enforces the >= 2 s spacing
	// and the CRC-16 check.
	tMin, tMax, hMin, hMax := float32(1000), float32(-1000), float32(1000), float32(-1000)
	for i := 1; i <= 12; i++ {
		var t, rh float32
		if i%10 == 0 {
			t, rh, err = chip.ReadFresh() // Real-time value, () → (float32, float32, error)
		} else {
			t, rh, err = chip.Read() // Read temperature and humidity, () → (float32, float32, error)
		}
		chk(err)
		if t < tMin {
			tMin = t
		}
		if t > tMax {
			tMax = t
		}
		if rh < hMin {
			hMin = rh
		}
		if rh > hMax {
			hMax = rh
		}
		// Magnus formula
		g := math.Log(float64(max(rh, 0.1))/100.0) + 17.62*float64(t)/(243.12+float64(t))
		dew := 243.12 * g / (17.62 - g)
		fmt.Printf("#%d  %.1f C  %.1f %%RH  dew point %.1f C  (T %.1f..%.1f, RH %.1f..%.1f)\n", i, t, rh, dew, tMin, tMax, hMin, hMax)
		time.Sleep(3 * time.Second)
	}

	// --- User registers as a persistent counter ---
	chk(chip.WriteUserRegisters(12, 0xBEEF)) // Write user registers, (user1, user2 uint16) → error
	u1, u2, err := chip.ReadUserRegisters()  // Read user registers, () → (uint16, uint16, error)
	chk(err)
	fmt.Printf("user registers 0x%04X 0x%04X\n", u1, u2)
	fmt.Println("done")
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
