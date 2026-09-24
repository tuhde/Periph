//go:build linux && !tinygo

// VL53L1X demo — Linux host. Long-range doorway people counter with a split
// ROI: the sensor hangs overhead in a doorway (up to 2.5 m); two narrow 8x16
// ROIs (centre SPADs 167 and 231, the left/right half-array centres used by
// ST's own people-counting code) form two virtual beams. Each zone learns its
// floor distance, then counts as occupied when something is more than 300 mm
// closer; the order in which the zones become occupied tells IN from OUT.
// Every 30 s a signal/ambient snapshot is printed and strong sunlight switches
// to short distance mode. After 60 s or 50 events the full ROI is restored.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/tof"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	occupiedMm  = 300
	maxEvents   = 50
	maxTime     = 60 * time.Second
	snapshot    = 30 * time.Second
	brightMcps  = 5.0
	noTargetMm  = 4000
	baselineRun = 20
)

var zoneCentres = [2]uint8{167, 231} // left, right

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

// measure ranges one zone; ok is false for an invalid reading.
func measure(s *tof.VL53L1XFull, zone int) (uint16, bool) {
	must(s.SetROICenter(zoneCentres[zone])) // Set ROI centre, (spad) → error
	d, err := s.Distance()                  // Measure distance, () → (uint16 mm, error)
	must(err)
	return d, s.RangeValid() // Check last measurement, () → bool
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	must(err)
	conn, err := connection.NewI2CConnection(bus, tof.VL53L1XI2CAddress, nil, nil) // Create I2C connection, (bus=1, addr=0x29) → (*I2CConnection, error)
	must(err)
	defer conn.Close()
	s, err := tof.NewVL53L1XFull(conn) // Create VL53L1X Full driver, (conn) → (*VL53L1XFull, error)
	must(err)

	// --- Two virtual beams ---
	// Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps
	// the two zones fast enough to catch a walking person. An 8x16 ROI covers
	// one half of the SPAD array, so alternating the centre alternates beams.
	must(s.SetDistanceMode(tof.VL53L1XDistanceModeLong)) // Set distance mode, (mode) → error
	must(s.SetTimingBudget(33000))                       // Set timing budget, (budgetUs µs) → error
	must(s.SetROI(8, 16))                                // Set ROI size, (width SPADs, height SPADs) → error
	oc, _ := s.OpticalCenter()                           // Read optical-centre SPAD, () → (uint8, error)
	fmt.Println("optical centre SPAD", oc, "zone centres", zoneCentres)

	// --- Learn the empty doorway ---
	// Twenty readings per zone give the floor distance each beam sees when
	// nobody is there; invalid readings are ignored.
	var baseline [2]float32
	for zone := 0; zone < 2; zone++ {
		var sum, n float32
		for i := 0; i < baselineRun; i++ {
			if d, ok := measure(s, zone); ok {
				sum += float32(d)
				n++
			}
		}
		baseline[zone] = noTargetMm
		if n > 0 {
			baseline[zone] = sum / n
		}
	}
	fmt.Printf("baseline left %.0f mm, right %.0f mm\n", baseline[0], baseline[1])

	// --- Count crossings ---
	// A person entering blocks the left beam first, then the right one (and
	// the reverse when leaving). Once both beams clear, the recorded order
	// decides the direction.
	countIn, countOut, events := 0, 0, 0
	var sequence []int
	start := time.Now()
	lastSnapshot := start
	for events < maxEvents && time.Since(start) < maxTime {
		var readings [2]uint16
		var occupied [2]bool
		for zone := 0; zone < 2; zone++ {
			d, ok := measure(s, zone)
			readings[zone] = d
			occupied[zone] = ok && float32(d) < baseline[zone]-occupiedMm
			if occupied[zone] && len(sequence) < 2 && (len(sequence) == 0 || sequence[0] != zone) {
				sequence = append(sequence, zone)
			}
		}
		if !occupied[0] && !occupied[1] && len(sequence) > 0 {
			if len(sequence) == 2 && sequence[0] == 0 {
				countIn++
			} else if len(sequence) == 2 && sequence[0] == 1 {
				countOut++
			}
			events++
			fmt.Printf("IN %d OUT %d (left %d, right %d)\n", countIn, countOut, readings[0], readings[1])
			sequence = sequence[:0]
		}

		// --- Watch the light ---
		// Sunlight through an open door raises the ambient rate and eats
		// long-mode range; short mode keeps working up to ~1.3 m.
		if time.Since(lastSnapshot) >= snapshot {
			lastSnapshot = time.Now()
			_, err := s.Distance() // Measure distance, () → (uint16 mm, error)
			must(err)
			m, err := s.ReadMeasurement() // Read result block, () → (VL53L1XMeasurement, error)
			must(err)
			fmt.Printf("signal %.2f MCPS, ambient %.2f MCPS\n", m.SignalRateMCPS, m.AmbientRateMCPS)
			mode, _ := s.DistanceMode() // Read distance mode, () → (VL53L1XDistanceMode, error)
			if m.AmbientRateMCPS > brightMcps && mode == tof.VL53L1XDistanceModeLong {
				must(s.SetDistanceMode(tof.VL53L1XDistanceModeShort)) // Set distance mode, (mode) → error
				fmt.Println("bright ambient light: switched to short distance mode")
			}
		}
	}

	// --- Restore the full field of view ---
	must(s.SetROI(16, 16)) // Set ROI size, (width SPADs, height SPADs) → error
	fmt.Printf("done: IN %d OUT %d\n", countIn, countOut)
}
