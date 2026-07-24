//go:build linux && !tinygo

package pressure

import "math"

func powImpl(x, y float64) float64 {
	return math.Pow(x, y)
}
