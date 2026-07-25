//go:build linux && !tinygo

// RDA5807M demo example — Linux host.
//
// FM band scanner. Starting at the bottom of the world-wide band, repeatedly
// calls Seek(up=true) until the band limit is reached. For each station
// found, prints the frequency, RSSI, and mono/stereo status. If RDS is
// enabled and a group arrives, attempts to assemble the 8-character Program
// Service (station) name.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/transport"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		panic(err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x10"), 0, 8)
	if err != nil {
		panic(err)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr)) // Create I2C transport, (bus=1, addr=0x10) → (*I2CTransport, error)
	if err != nil {
		panic(err)
	}
	defer tr.Close()

	// --- FM band scanner ---
	// Start at the bottom of the world-wide band and repeatedly seek upward
	// with SKMODE=1 (stop at band limit, the Minimal/Full default) so a seek
	// that returns nil means the top of the band has been reached.
	fm, err := comms.NewRda5807mFull(tr, 87.5, 10) // Create RDA5807M driver, (transport, frequency_mhz=87.5, volume=10) → (*Rda5807mFull, error)
	if err != nil {
		panic(err)
	}

	if err := fm.EnableRds(true); err != nil { // Enable RDS/RBDS, (enable) → error
		panic(err)
	}

	fmt.Println("Scanning...")
	count := 0
	for {
		freq, err := fm.Seek(true) // Seek to next station, (up=true) → (*float64 MHz, error)
		if err != nil {
			panic(err)
		}
		if freq == nil {
			break
		}
		station, err := fm.IsStation() // Check real station, () → (bool, error)
		if err != nil {
			panic(err)
		}
		if !station {
			continue
		}

		rssi, err := fm.SignalStrength() // Read RSSI, () → (uint8 0–127, error)
		if err != nil {
			panic(err)
		}
		stereo, err := fm.IsStereo() // Check stereo indicator, () → (bool, error)
		if err != nil {
			panic(err)
		}

		// --- Try to read the Program Service (station) name via RDS ---
		// Group types 0A/0B carry the 8-character PS name, four segments of
		// two characters each, addressed by block B bits 1:0. Give the
		// decoder up to 2 seconds to assemble a full name.
		psChars := [8]*rune{}
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			rr, err := fm.RdsReady() // Check RDS group ready, () → (bool, error)
			if err != nil {
				break
			}
			if rr {
				group, err := fm.ReadRdsGroup() // Read raw RDS blocks, () → (*[4]uint16, error)
				if err != nil || group == nil {
					break
				}
				blockB := group[1]
				blockD := group[3]
				groupType := blockB >> 12
				isB := (blockB >> 11) & 1
				if groupType == 0 && isB == 0 {
					seg := int(blockB & 0x03)
					c1 := rune((blockD >> 8) & 0xFF)
					c2 := rune(blockD & 0xFF)
					psChars[seg*2] = &c1
					psChars[seg*2+1] = &c2
					full := true
					for _, c := range psChars {
						if c == nil {
							full = false
							break
						}
					}
					if full {
						break
					}
				}
			}
			time.Sleep(40 * time.Millisecond)
		}

		name := "(no RDS name)"
		if allSet(psChars) {
			name = string([]rune{
				*psChars[0], *psChars[1], *psChars[2], *psChars[3],
				*psChars[4], *psChars[5], *psChars[6], *psChars[7],
			})
		}
		st := "mono"
		if stereo {
			st = "stereo"
		}
		fmt.Printf("%.2f MHz  RSSI=%d  %s  %s\n", *freq, rssi, st, trim(name)) // Seek to next station, (up=true) → (*float64 MHz, error)
		count++
	}

	fmt.Println()
	fmt.Printf("Scan complete: %d station(s) found\n", count)
}

func allSet(chars [8]*rune) bool {
	for _, c := range chars {
		if c == nil {
			return false
		}
	}
	return true
}

func trim(s string) string {
	start := 0
	end := len(s)
	for start < end && (s[start] == ' ' || s[start] == 0) {
		start++
	}
	for end > start && (s[end-1] == ' ' || s[end-1] == 0) {
		end--
	}
	return s[start:end]
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
