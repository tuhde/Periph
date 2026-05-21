#pragma once
#include <zephyr/kernel.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/drivers/gpio.h>
#include <cstring>
#include "Transport.h"

/** @brief UART transport for Zephyr RTOS (interrupt-driven UART API).
 *
 * Uses the Zephyr interrupt-driven UART API to implement non-blocking TX
 * with TX-complete signalling via a semaphore, and RX accumulation in an
 * interrupt callback.
 *
 * prj.conf must enable:
 *   CONFIG_UART_INTERRUPT_DRIVEN=y
 *   CONFIG_GPIO=y              (RS-485 only)
 *   CONFIG_CPP=y
 *   CONFIG_STD_CPP17=y
 *
 * The UART device node must be present and enabled in the board's devicetree
 * or an overlay.
 *
 * @param dev    UART device pointer obtained via DEVICE_DT_GET().
 * @param de_gpio RS-485 DE GPIO spec from GPIO_DT_SPEC_GET(); zero-init
 *               (port == NULL) disables RS-485 mode.
 */
class UARTTransportZephyr : public Transport {
public:
    UARTTransportZephyr(const struct device* dev,
                        const struct gpio_dt_spec& de_gpio = {})
        : _dev(dev), _de_gpio(de_gpio)
    {
        k_sem_init(&_tx_done, 0, 1);
        k_sem_init(&_rx_ready, 0, 1);
        uart_irq_callback_user_data_set(_dev, _uart_isr, this);
        uart_irq_rx_enable(_dev);

        if (_de_gpio.port != nullptr)
            gpio_pin_configure_dt(&_de_gpio, GPIO_OUTPUT_INACTIVE);
    }

    /** @brief Transmit bytes; in RS-485 mode assert DE, fill TX FIFO via IRQ,
     *         wait for TX-complete semaphore, then deassert DE.
     *  @param data Pointer to the data buffer.
     *  @param len  Number of bytes to send.
     */
    void write(const uint8_t* data, size_t len) override {
        _tx_buf     = data;
        _tx_len     = len;
        _tx_pos     = 0;
        _tx_running = true;

        if (_de_gpio.port != nullptr)
            gpio_pin_set_dt(&_de_gpio, 1);

        uart_irq_tx_enable(_dev);
        k_sem_take(&_tx_done, K_FOREVER);

        if (_de_gpio.port != nullptr)
            gpio_pin_set_dt(&_de_gpio, 0);
    }

    /** @brief Receive @p len bytes; blocks until all bytes have arrived in the
     *         RX accumulation buffer or the kernel timeout expires.
     *  @param buf Destination buffer; must be at least @p len bytes.
     *  @param len Number of bytes to read.
     */
    void read(uint8_t* buf, size_t len) override {
        _rx_buf  = buf;
        _rx_len  = len;
        _rx_pos  = 0;

        k_sem_take(&_rx_ready, K_SECONDS(1));
        // _rx_pos bytes have been filled; caller checks length externally.
    }

    /** @brief Transmit bytes then receive @p buf_len bytes.
     *
     *  DE is asserted only during the transmit phase.
     *
     *  @param data     Command bytes to send.
     *  @param data_len Number of bytes in @p data.
     *  @param buf      Destination buffer for the read phase.
     *  @param buf_len  Number of bytes to read.
     */
    void write_read(const uint8_t* data, size_t data_len,
                    uint8_t* buf, size_t buf_len) override {
        write(data, data_len);
        read(buf, buf_len);
    }

private:
    const struct device*   _dev;
    const struct gpio_dt_spec _de_gpio;

    struct k_sem _tx_done;
    struct k_sem _rx_ready;

    volatile const uint8_t* _tx_buf     = nullptr;
    volatile size_t          _tx_len     = 0;
    volatile size_t          _tx_pos     = 0;
    volatile bool            _tx_running = false;

    uint8_t*        _rx_buf = nullptr;
    size_t          _rx_len = 0;
    volatile size_t _rx_pos = 0;

    static void _uart_isr(const struct device* dev, void* user_data) {
        auto* self = static_cast<UARTTransportZephyr*>(user_data);

        if (uart_irq_update(dev) < 0) return;

        if (uart_irq_tx_ready(dev) && self->_tx_running) {
            if (self->_tx_pos < self->_tx_len) {
                int sent = uart_fifo_fill(dev,
                    const_cast<const uint8_t*>(self->_tx_buf) + self->_tx_pos,
                    static_cast<int>(self->_tx_len - self->_tx_pos));
                if (sent > 0)
                    self->_tx_pos += static_cast<size_t>(sent);
            }
            if (self->_tx_pos >= self->_tx_len && uart_irq_tx_complete(dev)) {
                uart_irq_tx_disable(dev);
                self->_tx_running = false;
                k_sem_give(&self->_tx_done);
            }
        }

        if (uart_irq_rx_ready(dev) && self->_rx_buf != nullptr) {
            while (self->_rx_pos < self->_rx_len) {
                int got = uart_fifo_read(dev,
                    self->_rx_buf + self->_rx_pos,
                    static_cast<int>(self->_rx_len - self->_rx_pos));
                if (got <= 0) break;
                self->_rx_pos += static_cast<size_t>(got);
            }
            if (self->_rx_pos >= self->_rx_len) {
                self->_rx_buf = nullptr;
                k_sem_give(&self->_rx_ready);
            }
        }
    }
};
