#include "torx_radio_protocol.h"

static uint16_t read_u16_be(const uint8_t *p) { return (uint16_t)(((uint16_t)p[0] << 8) | p[1]); }
static uint32_t read_u32_be(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

uint32_t torx_radio_crc32(const uint8_t *data, size_t length) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < length; ++i) {
        crc ^= data[i];
        for (unsigned bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}

int torx_radio_decode(const uint8_t *data, size_t length, torx_radio_frame_view_t *out) {
    if (!data || !out || length < TORX_RADIO_HEADER_BYTES + TORX_RADIO_CRC_BYTES) return -1;
    if (read_u32_be(data) != TORX_RADIO_MAGIC || data[4] != TORX_RADIO_VERSION) return -2;
    const uint16_t payload_length = read_u16_be(data + 6);
    if (payload_length > TORX_RADIO_MAX_PAYLOAD || length != TORX_RADIO_HEADER_BYTES + payload_length + 4u) return -3;
    if (torx_radio_crc32(data, length - 4u) != read_u32_be(data + length - 4u)) return -4;
    if (data[5] < TORX_HELLO_REQUEST || data[5] > TORX_PONG) return -5;
    out->kind = (torx_radio_kind_t)data[5];
    out->sequence = read_u32_be(data + 8);
    out->payload_length = payload_length;
    out->payload = data + TORX_RADIO_HEADER_BYTES;
    return 0;
}
