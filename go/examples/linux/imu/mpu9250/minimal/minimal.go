//go:build linux && !tinygo

// MPU9250 minimal example — Linux host.
//
// Constructs the driver with a /dev/i2c-N connection, then loops reading
// 3-axis acceleration and angular rate once per second.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/imu"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x68"), 0, 8)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil) // Create I2C connection, (bus=1, addr=0x68) → (*I2CConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := imu.NewMPU9250Minimal(conn) // Create MPU9250 driver, (connection) → (*MPU9250Minimal, error)
	if err != nil {
		panic(err)
	}

	for {
		ax, ay, az, err := chip.Accel() // Read 3-axis acceleration, () → (float32 m/s², float32 m/s², float32 m/s², error)
		if err != nil {
			panic(err)
		}
		gx, gy, gz, err := chip.Gyro() // Read 3-axis angular rate, () → (float32 rad/s, float32 rad/s, float32 rad/s, error)
		if err != nil {
			panic(err)
		}
		fmt.Printf("a=(%6.2f %6.2f %6.2f) m/s2  g=(%7.3f %7.3f %7.3f) rad/s\n", ax, ay, az, gx, gy, gz)
		time.Sleep(time.Second)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
