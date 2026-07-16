#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "UARTTransportZephyr.h"
#include "NEO6.h"

#ifndef NEO6_UART_NODE
#define NEO6_UART_NODE DT_NODELABEL(uart1)
#endif

int main(void) {
    const struct device* dev = DEVICE_DT_GET(NEO6_UART_NODE);
    UARTTransportZephyr transport(dev);
    NEO6Minimal gps(transport);                          // Create NEO-6 driver, (transport, bus_type=Uart)

    while (true) {
        if (gps.update()) {                              // Read + parse one NMEA sentence, () → bool
            printk("%d.%06d, %d.%06d, %d\n",
                   (int)gps.latitude(), (int)(gps.latitude() * 1000000) % 1000000,
                   (int)gps.longitude(), (int)(gps.longitude() * 1000000) % 1000000,
                   (int)gps.altitude());
        }
        k_msleep(50);
    }
    return 0;
}
