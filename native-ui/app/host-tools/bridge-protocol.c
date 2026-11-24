#define _POSIX_C_SOURCE 200809L

#include "bridge-protocol.h"

#include <errno.h>

enum { MAX_PAYLOAD = 65536, FRAME_HEADER = 5, MAX_REPLY = 8192 };

size_t nx_length(const char *text) {
    size_t size = 0;
    while (text[size]) {
        ++size;
    }
    return size;
}

bool nx_equal(const char *a, const char *b) {
    while (*a && *a == *b) {
        ++a;
        ++b;
    }
    return *a == *b;
}

bool nx_prefix(const char *text, const char *prefix) {
    while (*prefix) {
        if (*text++ != *prefix++) {
            return false;
        }
    }
    return true;
}

const char *nx_env(char *const env[], const char *key) {
    size_t length = nx_length(key);
    for (; *env; ++env) {
        if (nx_prefix(*env, key) && (*env)[length] == '=') {
            return *env + length + 1;
        }
    }
    return NULL;
}

static void append_byte(struct nx_buffer *buffer, unsigned char byte) {
    if (buffer->length == buffer->capacity) {
        buffer->invalid = true;
        return;
    }
    buffer->data[buffer->length++] = (char)byte;
}

void nx_append(struct nx_buffer *buffer, const char *text) {
    while (*text && !buffer->invalid) {
        append_byte(buffer, (unsigned char)*text++);
    }
}

// 校验单个 UTF-8 码点，拒绝过长编码、代理区和超出 Unicode 范围的值。
static size_t utf8_width(const unsigned char *s, size_t size) {
    unsigned first = s[0];
    if (first < 0x80) {
        return 1;
    }
    size_t width;
    if (first >= 0xc2 && first <= 0xdf) {
        width = 2;
    } else if (first >= 0xe0 && first <= 0xef) {
        width = 3;
    } else if (first >= 0xf0 && first <= 0xf4) {
        width = 4;
    } else {
        return 0;
    }
    if (width > size) {
        return 0;
    }
    for (size_t i = 1; i < width; ++i) {
        if ((s[i] & 0xc0) != 0x80) {
            return 0;
        }
    }
    if ((first == 0xe0 && s[1] < 0xa0) || (first == 0xed && s[1] >= 0xa0)
        || (first == 0xf0 && s[1] < 0x90) || (first == 0xf4 && s[1] >= 0x90)) {
        return 0;
    }
    return width;
}

void nx_json_string(struct nx_buffer *buffer, const char *text, size_t length) {
    static const char hex[] = "0123456789abcdef";
    const unsigned char *bytes = (const unsigned char *)text;
    append_byte(buffer, '"');
    for (size_t i = 0; i < length && !buffer->invalid;) {
        unsigned char byte = bytes[i];
        if (byte == '"' || byte == '\\') {
            append_byte(buffer, '\\');
            append_byte(buffer, byte);
            ++i;
        } else if (byte < 0x20) {
            nx_append(buffer, "\\u00");
            append_byte(buffer, (unsigned char)hex[byte >> 4]);
            append_byte(buffer, (unsigned char)hex[byte & 15]);
            ++i;
        } else {
            size_t width = utf8_width(bytes + i, length - i);
            if (!width) {
                buffer->invalid = true;
                return;
            }
            for (size_t end = i + width; i < end; ++i) {
                append_byte(buffer, bytes[i]);
            }
        }
    }
    append_byte(buffer, '"');
}

void nx_number(struct nx_buffer *buffer, unsigned value) {
    char digits[10];
    size_t count = 0;
    do {
        digits[count++] = (char)('0' + value % 10);
        value /= 10;
    } while (value);
    while (count) {
        append_byte(buffer, (unsigned char)digits[--count]);
    }
}

static bool write_all(int fd, const char *data, size_t size) {
    while (size) {
        long count = nx_write(fd, data, size);
        if (count == -EINTR) {
            continue;
        }
        if (count <= 0) {
            return false;
        }
        data += count;
        size -= (size_t)count;
    }
    return true;
}

void nx_error(const char *message) {
    write_all(2, message, nx_length(message));
    write_all(2, "\n", 1);
}

bool nx_send_header(int socket, const struct nx_buffer *request) {
    if (request->invalid) {
        return false;
    }
    size_t sent = 0;
    while (sent < request->length) {
        long count = nx_send(socket, request->data + sent, request->length - sent, false);
        if (count == -EINTR) {
            continue;
        }
        if (count <= 0) {
            return false;
        }
        sent += (size_t)count;
    }
    return true;
}

struct json_reader {
    const unsigned char *current;
    const unsigned char *end;
};

static void space(struct json_reader *reader) {
    while (reader->current < reader->end) {
        unsigned char c = *reader->current;
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
            break;
        }
        ++reader->current;
    }
}

static bool consume(struct json_reader *reader, unsigned char byte) {
    if (reader->current == reader->end || *reader->current != byte) {
        return false;
    }
    ++reader->current;
    return true;
}

static bool literal(struct json_reader *reader, const char *value) {
    size_t length = nx_length(value);
    if ((size_t)(reader->end - reader->current) < length) {
        return false;
    }
    for (size_t i = 0; i < length; ++i) {
        if (reader->current[i] != (unsigned char)value[i]) {
            return false;
        }
    }
    reader->current += length;
    return true;
}

static int hex_quad(struct json_reader *reader) {
    unsigned result = 0;
    for (int i = 0; i < 4; ++i) {
        if (reader->current == reader->end) {
            return -1;
        }
        unsigned char c = *reader->current++;
        unsigned digit;
        if (c >= '0' && c <= '9') {
            digit = c - '0';
        } else if ((c | 32) >= 'a' && (c | 32) <= 'f') {
            digit = (unsigned)(c | 32) - 'a' + 10;
        } else {
            return -1;
        }
        result = 16 * result + digit;
    }
    return (int)result;
}

static bool unicode_escape(struct json_reader *reader, struct nx_buffer *text) {
    int code = hex_quad(reader);
    if (code < 0) {
        return false;
    }
    if (code >= 0xd800 && code <= 0xdbff) {
        if (!literal(reader, "\\u")) {
            return false;
        }
        int low = hex_quad(reader);
        if (low < 0xdc00 || low > 0xdfff) {
            return false;
        }
        code = 0x10000 + (code - 0xd800) * 1024 + (low - 0xdc00);
    } else if (code >= 0xdc00 && code <= 0xdfff) {
        return false;
    }
    if (!text) {
        return true;
    }
    if (code >= 0x10000) {
        append_byte(text, (unsigned char)(0xf0 | (code >> 18)));
        append_byte(text, (unsigned char)(0x80 | ((code >> 12) & 63)));
    } else if (code >= 0x800) {
        append_byte(text, (unsigned char)(0xe0 | (code >> 12)));
    } else if (code >= 0x80) {
        append_byte(text, (unsigned char)(0xc0 | (code >> 6)));
    }
    if (code >= 0x800) {
        append_byte(text, (unsigned char)(0x80 | ((code >> 6) & 63)));
    }
    append_byte(text, (unsigned char)(code < 0x80 ? code : 0x80 | (code & 63)));
    return true;
}

static bool json_string(struct json_reader *reader, struct nx_buffer *text) {
    if (!consume(reader, '"')) {
        return false;
    }
    while (reader->current < reader->end) {
        unsigned char first = *reader->current;
        if (consume(reader, '"')) {
            return true;
        }
        if (consume(reader, '\\')) {
            if (consume(reader, 'u')) {
                if (!unicode_escape(reader, text)) {
                    return false;
                }
                continue;
            }
            static const char escapes[] = "\"\\/bfnrt";
            static const char decoded[] = "\"\\/\b\f\n\r\t";
            size_t i = 0;
            while (escapes[i] && !consume(reader, (unsigned char)escapes[i])) {
                ++i;
            }
            if (!escapes[i]) {
                return false;
            }
            if (text) {
                append_byte(text, (unsigned char)decoded[i]);
            }
        } else {
            size_t width = utf8_width(reader->current, (size_t)(reader->end - reader->current));
            if (first < 0x20 || !width) {
                return false;
            }
            const unsigned char *end = reader->current + width;
            while (reader->current < end) {
                if (text) {
                    append_byte(text, *reader->current);
                }
                ++reader->current;
            }
        }
    }
    return false;
}

static bool digits(struct json_reader *reader) {
    const unsigned char *start = reader->current;
    while (reader->current < reader->end && *reader->current >= '0' && *reader->current <= '9') {
        ++reader->current;
    }
    return reader->current != start;
}

static bool json_number(struct json_reader *reader) {
    consume(reader, '-');
    if (!consume(reader, '0') && !digits(reader)) {
        return false;
    }
    if (consume(reader, '.') && !digits(reader)) {
        return false;
    }
    if (consume(reader, 'e') || consume(reader, 'E')) {
        if (!consume(reader, '+')) {
            consume(reader, '-');
        }
        return digits(reader);
    }
    return true;
}

static bool skip_value(struct json_reader *reader, unsigned depth) {
    space(reader);
    if (depth > 16 || reader->current == reader->end) {
        return false;
    }
    if (*reader->current == '"') {
        return json_string(reader, NULL);
    }
    bool object = consume(reader, '{');
    if (object || consume(reader, '[')) {
        unsigned char end = object ? '}' : ']';
        space(reader);
        if (consume(reader, end)) {
            return true;
        }
        do {
            space(reader);
            if (object) {
                if (!json_string(reader, NULL)) {
                    return false;
                }
                space(reader);
                if (!consume(reader, ':')) {
                    return false;
                }
            }
            if (!skip_value(reader, depth + 1)) {
                return false;
            }
            space(reader);
        } while (consume(reader, ','));
        return consume(reader, end);
    }
    return literal(reader, "null") || literal(reader, "true") || literal(reader, "false") || json_number(reader);
}

static bool json_key(const struct nx_buffer *key, const char *wanted) {
    if (key->invalid || key->length != nx_length(wanted)) {
        return false;
    }
    for (size_t i = 0; i < key->length; ++i) {
        if (key->data[i] != wanted[i]) {
            return false;
        }
    }
    return true;
}

// 服务端允许附加字段。只认顶层布尔 ok 以及字符串 message / error。
static int parse_reply(const unsigned char *line, size_t length, struct nx_buffer *message) {
    struct json_reader reader = {line, line + length};
    int ok = -1;
    bool has_message = false;
    space(&reader);
    if (!consume(&reader, '{')) {
        return -1;
    }
    do {
        char name[16];
        struct nx_buffer key = {name, sizeof(name), 0, false};
        space(&reader);
        if (!json_string(&reader, &key)) {
            return -1;
        }
        space(&reader);
        if (!consume(&reader, ':')) {
            return -1;
        }
        space(&reader);
        if (json_key(&key, "ok")) {
            if (ok >= 0) {
                return -1;
            }
            if (literal(&reader, "true")) {
                ok = 1;
            } else if (literal(&reader, "false")) {
                ok = 0;
            } else {
                return -1;
            }
        } else if (reader.current < reader.end && *reader.current == '"'
                   && (json_key(&key, "message") || (!has_message && json_key(&key, "error")))) {
            has_message = json_key(&key, "message");
            message->length = 0;
            if (!json_string(&reader, message)) {
                return -1;
            }
        } else if (!skip_value(&reader, 0)) {
            return -1;
        }
        space(&reader);
    } while (consume(&reader, ','));
    if (!consume(&reader, '}')) {
        return -1;
    }
    space(&reader);
    return reader.current == reader.end ? ok : -1;
}

bool nx_accept_reply(int socket) {
    static unsigned char line[MAX_REPLY];
    static char text[MAX_REPLY];
    size_t length = 0;
    while (length < sizeof(line)) {
        long count = nx_read(socket, line + length, 1);
        if (count == -EINTR) {
            continue;
        }
        if (count != 1) {
            break;
        }
        if (line[length] == '\n') {
            struct nx_buffer message = {text, sizeof(text), 0, false};
            int ok = parse_reply(line, length, &message);
            if (ok == 1) {
                return true;
            }
            if (ok == 0 && message.length && !message.invalid) {
                write_all(2, message.data, message.length);
                write_all(2, "\n", 1);
                return false;
            }
            if (ok == 0) {
                nx_error("桥接服务拒绝请求。");
                return false;
            }
            break;
        }
        ++length;
    }
    nx_error("桥接服务回复无效或已断开。");
    return false;
}

static uint32_t get_be32(const unsigned char bytes[4]) {
    return (uint32_t)bytes[0] << 24 | (uint32_t)bytes[1] << 16 | (uint32_t)bytes[2] << 8 | bytes[3];
}

void nx_be32(unsigned char bytes[4], uint32_t value) {
    bytes[0] = (unsigned char)(value >> 24);
    bytes[1] = (unsigned char)(value >> 16);
    bytes[2] = (unsigned char)(value >> 8);
    bytes[3] = (unsigned char)value;
}

struct incoming_frame {
    unsigned char bytes[FRAME_HEADER + MAX_PAYLOAD];
    size_t received;
    size_t expected;
    size_t delivered;
};

// 先读完整帧头再按长度读载荷。限制单帧缓冲，服务端背压时仍可转发 stdin 和信号。
static bool receive_frame(int socket, struct incoming_frame *frame) {
    long count = nx_receive(socket, frame->bytes + frame->received, frame->expected - frame->received);
    if (count == -EINTR || count == -EAGAIN) {
        return true;
    }
    if (count <= 0) {
        return false;
    }
    frame->received += (size_t)count;
    if (frame->expected == FRAME_HEADER && frame->received == FRAME_HEADER) {
        unsigned kind = frame->bytes[0];
        uint32_t length = get_be32(frame->bytes + 1);
        if (length > MAX_PAYLOAD || (kind == NX_FRAME_EXIT && length != 4)
            || (kind != NX_FRAME_EXIT && kind != NX_FRAME_STDOUT && kind != NX_FRAME_STDERR)) {
            return false;
        }
        frame->expected += length;
    }
    return true;
}

struct relay_channel {
    int fd;
    bool socket;
};

static struct relay_channel stdio_channel(int fd) {
    struct relay_channel channel = {fd, false};
    struct stat original;
    if (!nx_stat(fd, &original)) {
        return channel;
    }
    channel.socket = S_ISSOCK(original.st_mode);
#ifdef __linux__
    // 为管道和终端建立独立的非阻塞描述，保留继承描述符的标志与常规文件的偏移。
    if (S_ISFIFO(original.st_mode) || (S_ISCHR(original.st_mode) && nx_is_tty(fd))) {
        char path[] = "/proc/self/fd/0";
        path[sizeof(path) - 2] += (char)fd;
        int reopened = nx_open_nonblocking(path, fd);
        if (reopened >= 0) {
            struct stat actual;
            if (nx_stat(reopened, &actual) && actual.st_dev == original.st_dev && actual.st_ino == original.st_ino) {
                channel.fd = reopened;
            } else {
                nx_close(reopened);
            }
        }
    }
#endif
    return channel;
}

static bool flush_output(struct incoming_frame *frame, struct relay_channel output) {
    size_t size = frame->expected - frame->delivered;
    // 保证兼容无法独立重新打开的继承管道的最小 PIPE_BUF。
    size_t chunk = output.socket || output.fd >= 3 || size < 512 ? size : 512;
    const unsigned char *data = frame->bytes + frame->delivered;
    long count = output.socket ? nx_send(output.fd, data, chunk, true) : nx_write(output.fd, data, chunk);
    if (count == -EINTR || count == -EAGAIN) {
        return true;
    }
    if (count <= 0) {
        return false;
    }
    frame->delivered += (size_t)count;
    return true;
}

int nx_relay(int socket, int failure_status, bool map_negative_exit) {
    // 握手结束后启用非阻塞。Darwin 的 Unix socket 发送不能只依赖 MSG_DONTWAIT。
    if (!nx_nonblocking_socket(socket)) {
        nx_error("无法启用桥接 socket 的非阻塞模式。");
        return failure_status;
    }
    static struct incoming_frame incoming;
    incoming.expected = FRAME_HEADER;
    incoming.delivered = FRAME_HEADER;
    static unsigned char outgoing[FRAME_HEADER + MAX_PAYLOAD];
    const struct relay_channel input = stdio_channel(0);
    const struct relay_channel standard_output = stdio_channel(1);
    const struct relay_channel standard_error = stdio_channel(2);
    bool stdin_open = true;
    size_t queued = 0;
    size_t sent = 0;

    for (;;) {
        if (incoming.received == incoming.expected) {
            if (incoming.bytes[0] == NX_FRAME_EXIT) {
                uint32_t status = get_be32(incoming.bytes + FRAME_HEADER);
                if (map_negative_exit && status > INT32_MAX) {
                    status = 128u - status;
                }
                return (int)(status & 255u);
            }
            if (incoming.delivered == incoming.expected) {
                incoming.received = 0;
                incoming.expected = FRAME_HEADER;
                incoming.delivered = FRAME_HEADER;
            }
        }
        bool output_ready = incoming.received == incoming.expected;
        if (sent == queued) {
            sent = queued = 0;
            unsigned control = nx_pending_control(outgoing + FRAME_HEADER);
            if (control) {
                outgoing[0] = (unsigned char)control;
                nx_be32(outgoing + 1, 4);
                queued = FRAME_HEADER + 4;
            }
        }
        struct relay_channel output = incoming.bytes[0] == NX_FRAME_STDOUT ? standard_output : standard_error;
        struct pollfd fds[] = {
            {socket, (short)((output_ready ? 0 : POLLIN) | (queued ? POLLOUT : 0)), 0},
            {stdin_open && !queued ? input.fd : -1, POLLIN, 0},
            {output_ready ? output.fd : -1, POLLOUT, 0},
        };
        if (!fds[0].events) {
            fds[0].fd = -1;
        }
        long result = nx_poll(fds, sizeof(fds) / sizeof(fds[0]));
        if (result == -EINTR) {
            continue;
        }
        if (result < 0) {
            break;
        }
        if (!output_ready && fds[0].revents && !receive_frame(socket, &incoming)) {
            break;
        }
        if (output_ready && fds[2].revents && !flush_output(&incoming, output)) {
            break;
        }
        // 完整的退出帧优先于向可能已经关闭的服务端写入。
        if (incoming.received == incoming.expected && incoming.bytes[0] == NX_FRAME_EXIT) {
            continue;
        }
        if (queued && (fds[0].revents & (POLLOUT | POLLHUP | POLLERR | POLLNVAL))) {
            long count = nx_send(socket, outgoing + sent, queued - sent, true);
            if (count > 0) {
                sent += (size_t)count;
            } else if (count != -EINTR && count != -EAGAIN) {
                // 允许服务端半关闭写入方向后继续交付已排队的退出帧。
                stdin_open = false;
                sent = queued;
            }
        }
        if (fds[1].revents) {
            unsigned char *payload = outgoing + FRAME_HEADER;
            long count = input.socket ? nx_receive(input.fd, payload, MAX_PAYLOAD) : nx_read(input.fd, payload, MAX_PAYLOAD);
            if (count == -EINTR || count == -EAGAIN) {
                continue;
            }
            if (count < 0 && count != -EBADF) {
                break;
            }
            if (count <= 0) {
                stdin_open = false;
                count = 0;
            }
            outgoing[0] = count ? NX_FRAME_STDIN : NX_FRAME_EOF;
            nx_be32(outgoing + 1, (uint32_t)count);
            queued = FRAME_HEADER + (size_t)count;
        }
    }
    nx_error("桥接帧无效或连接在收到退出码前中断。");
    return failure_status;
}
