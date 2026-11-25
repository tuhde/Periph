//go:build linux && !tinygo

// ICM20948 minimal example — Linux host.
//
// Constructs the driver, then loops reading 3-axis acceleration and angular
// rate ten times per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	must(err)

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	must(err)
	defer conn.Close()

	chip, err := imu.NewICM20948Minimal(conn) // Create ICM20948 driver, (connection) → (*ICM20948Minimal, error)
	must(err)

	for {
		ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32, float32, float32 m/s², error)
		must(err)
		gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32, float32, float32 rad/s, error)
		must(err)
		fmt.Printf("accel=(%.2f %.2f %.2f) m/s2  gyro=(%.3f %.3f %.3f) rad/s\n", ax, ay, az, gx, gy, gz)
		time.Sleep(100 * time.Millisecond)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
