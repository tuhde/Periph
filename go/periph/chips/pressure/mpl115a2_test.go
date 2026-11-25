package pressure

import (
	"math"
	"testing"
)

// Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
// ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
func newMpl115a2Mock() *mockConnection {
	c := newMockConnection()
	c.setRegister(mpl115a2RegCoef, 0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8)
	c.setRegister(mpl115a2RegPadc, 0x66, 0x80, 0x7E, 0xC0)
	return c
}

func TestMPL115A2FullAPI(t *testing.T) {
	conn := newMpl115a2Mock()
	d, err := NewMpl115a2Full(conn)
	if err != nil {
		t.Fatal(err)
	}

	a0, b1, b2, c12 := d.Coefficients()
	if math.Abs(a0-2009.75) > 1e-6 || math.Abs(b1+2.375854) > 1e-4 || math.Abs(b2+0.92047) > 1e-4 || math.Abs(c12-0.000790) > 1e-5 {
		t.Errorf("coefficients = %v %v %v %v", a0, b1, b2, c12)
	}

	p, err := d.ReadPressure()
	if err != nil || math.Abs(p-96586) > 30 {
		t.Errorf("pressure = %v, %v", p, err)
	}
	w := lastWriteTo(conn.writes, mpl115a2RegConvert)
	if len(w) != 2 || w[1] != 0x00 {
		t.Errorf("CONVERT write = %v", w)
	}

	tc, err := d.ReadTemperature()
	if err != nil || math.Abs(tc-(25.0+9.0/-5.35)) > 1e-9 {
		t.Errorf("temperature = %v, %v", tc, err)
	}
	padc, tadc, err := d.ReadRaw()
	if err != nil || padc != 410 || tadc != 507 {
		t.Errorf("raw = %v %v %v", padc, tadc, err)
	}
	pp, tt, err := d.Read()
	if err != nil || math.Abs(pp-p) > 1e-6 || math.Abs(tt-tc) > 1e-9 {
		t.Errorf("read = %v %v %v", pp, tt, err)
	}
	if math.Abs(d.Compensate(410, 507)-p) > 1e-6 {
		t.Error("compensate mismatch")
	}
	if d.Compensate(0, 0) < 40000 || d.Compensate(1023, 0) > 130000 {
		t.Error("compensate limits")
	}
	hpa, err := d.PressureHpa()
	if err != nil || math.Abs(hpa-p/100) > 1e-3 {
		t.Errorf("hpa = %v, %v", hpa, err)
	}
	alt, err := d.Altitude(101325.0)
	if err != nil || math.Abs(alt-44330.0*(1.0-math.Pow(p/101325.0, 1.0/5.255))) > 0.1 {
		t.Errorf("altitude = %v, %v", alt, err)
	}
	if alt0, _ := d.Altitude(p); math.Abs(alt0) > 0.1 {
		t.Errorf("altitude at own pressure = %v", alt0)
	}

	conn.setRegister(mpl115a2RegCoef, 0x00, 0x08, 0, 0, 0, 0, 0, 0)
	if err := d.ReloadCoefficients(); err != nil {
		t.Fatal(err)
	}
	if a, _, _, _ := d.Coefficients(); a != 1.0 {
		t.Errorf("reloaded a0 = %v", a)
	}
}

func TestMPL115A2Minimal(t *testing.T) {
	d, err := NewMpl115a2Minimal(newMpl115a2Mock())
	if err != nil {
		t.Fatal(err)
	}
	if p, err := d.ReadPressure(); err != nil || math.Abs(p-96586) > 30 {
		t.Errorf("pressure = %v, %v", p, err)
	}
}
