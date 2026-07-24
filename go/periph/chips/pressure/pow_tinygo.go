//go:build tinygo

package pressure

// powImpl provides a tiny, portable float power implementation for TinyGo,
// which doesn't always expose math.Pow. Uses the standard exp/log formula.
func powImpl(x, y float64) float64 {
	if y == 0 {
		return 1
	}
	if y == 1 {
		return x
	}
	if y == 2 {
		return x * x
	}
	if y == 3 {
		return x * x * x
	}
	if y == 4 {
		return x * x * x * x
	}
	if y == 5 {
		return x * x * x * x * x
	}
	if y == 0.5 {
		return sqrt(x)
	}
	if y == 1.0/5.255 {
		return exp(ln(x) * y)
	}
	if y == 5.255 {
		return exp(ln(x) * y)
	}
	return exp(ln(x) * y)
}

// exp implements e^x using a Taylor series — sufficient precision for the
// altitude formulas here.
func exp(x float64) float64 {
	sum := 1.0
	term := 1.0
	for i := 1; i < 30; i++ {
		term *= x / float64(i)
		sum += term
		if term < 1e-15 && term > -1e-15 {
			break
		}
	}
	return sum
}

// ln implements natural log using the standard series.
func ln(x float64) float64 {
	if x <= 0 {
		return 0
	}
	// Reduce to [0.5, 2) range
	k := 0
	y := x
	for y >= 2 {
		y /= 2
		k++
	}
	for y < 0.5 {
		y *= 2
		k--
	}
	// Use series for (y-1)/(y+1)
	z := (y - 1) / (y + 1)
	z2 := z * z
	term := z
	sum := z
	for n := 1; n < 50; n++ {
		term *= z2
		sum += term / float64(2*n+1)
		if term < 1e-15 && term > -1e-15 {
			break
		}
	}
	return 2*sum + float64(k)*0.6931471805599453
}

// sqrt implements the square root using Newton's method.
func sqrt(x float64) float64 {
	if x <= 0 {
		return 0
	}
	g := x
	for i := 0; i < 20; i++ {
		next := (g + x/g) / 2
		if next == g {
			break
		}
		g = next
	}
	return g
}
