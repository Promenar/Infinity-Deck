#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE

#include "bridge-posix.h"

#include <arpa/inet.h>
#include <stdlib.h>
#include <string.h>

enum { SSH_FAILURE = 255, MAX_REQUEST = 8192 };
static const char bridge_prefix[] = "127.0.0.1:";

struct ssh_args {
    char *host;
    const char *user;
    const char *command;
    unsigned port;
};

static unsigned parse_port(const char *text) {
    unsigned port = 0;
    for (; *text; ++text) {
        if (*text < '0' || *text > '9') {
            return 0;
        }
        port = port * 10 + (unsigned)(*text - '0');
        if (port > 65535) {
            return 0;
        }
    }
    return port;
}

static bool safe_option(const char *option) {
    return nx_equal(option, "SendEnv=GIT_PROTOCOL") || nx_equal(option, "BatchMode=yes")
        || nx_equal(option, "StrictHostKeyChecking=yes");
}

static bool parse_arguments(int argc, char **argv, struct ssh_args *options) {
    int index = 1;
    for (; index < argc && argv[index][0] == '-'; ++index) {
        const char *flag = argv[index];
        if (nx_equal(flag, "--")) {
            ++index;
            break;
        }
        if (nx_equal(flag, "-4") || nx_equal(flag, "-6")) {
            continue;
        }
        if (flag[1] != 'p' && flag[1] != 'l' && flag[1] != 'o') {
            return false;
        }
        const char *value = flag + 2;
        if (!*value) {
            if (++index == argc) {
                return false;
            }
            value = argv[index];
        }
        switch (flag[1]) {
        case 'p':
            options->port = parse_port(value);
            if (!options->port) {
                return false;
            }
            break;
        case 'l':
            if (!*value) {
                return false;
            }
            options->user = value;
            break;
        case 'o':
            if (!safe_option(value)) {
                return false;
            }
            break;
        }
    }
    if (argc - index != 2) {
        return false;
    }
    options->host = argv[index];
    options->command = argv[index + 1];
    char *at = strrchr(options->host, '@');
    if (at) {
        *at = '\0';
        if (!options->user && *options->host) {
            options->user = options->host;
        }
        options->host = at + 1;
    }
    size_t host_length = nx_length(options->host);
    if (host_length >= 2 && options->host[0] == '[' && options->host[host_length - 1] == ']') {
        options->host[host_length - 1] = '\0';
        ++options->host;
    }
    return *options->host && *options->command;
}

static void request_header(struct nx_buffer *request, const struct ssh_args *options, const char *nonce) {
    const char *version = getenv("GIT_PROTOCOL");
    unsigned protocol = 0;
    if (version && nx_equal(version, "version=2")) {
        protocol = 2;
    } else if (version && nx_equal(version, "version=1")) {
        protocol = 1;
    }
    nx_append(request, "{\"nonce\":");
    nx_json_string(request, nonce, nx_length(nonce));
    nx_append(request, ",\"host\":");
    nx_json_string(request, options->host, nx_length(options->host));
    nx_append(request, ",\"port\":");
    if (options->port) {
        nx_number(request, options->port);
    } else {
        nx_append(request, "null");
    }
    nx_append(request, ",\"user\":");
    if (options->user) {
        nx_json_string(request, options->user, nx_length(options->user));
    } else {
        nx_append(request, "null");
    }
    nx_append(request, ",\"command\":");
    nx_json_string(request, options->command, nx_length(options->command));
    nx_append(request, ",\"protocol\":");
    nx_number(request, protocol);
    nx_append(request, "}\n");
}

long nx_poll(struct pollfd *fds, size_t count) {
    int result = poll(fds, (nfds_t)count, -1);
    return result < 0 ? -errno : result;
}

unsigned nx_pending_control(unsigned char payload[4]) {
    (void)payload;
    return 0;
}

int main(int argc, char **argv) {
    // Git 在自定义 GIT_SSH 首次调用前用 -G 探测 OpenSSH 参数形态。
    if (argc >= 3 && nx_equal(argv[1], "-G") && argv[argc - 1][0]) {
        return 0;
    }
    const char *bridge = getenv("INFINITY_GIT_SSH_BRIDGE");
    const char *nonce = getenv("INFINITY_GIT_NONCE");
    if (!bridge || !nonce || !*nonce) {
        nx_error("缺少 INFINITY_GIT_SSH_BRIDGE 或 INFINITY_GIT_NONCE。");
        return SSH_FAILURE;
    }
    unsigned bridge_port = nx_prefix(bridge, bridge_prefix) ? parse_port(bridge + sizeof(bridge_prefix) - 1) : 0;
    if (!bridge_port) {
        nx_error("SSH 桥地址必须为 127.0.0.1:<端口>。");
        return SSH_FAILURE;
    }
    struct ssh_args options = {0};
    if (!parse_arguments(argc, argv, &options)) {
        nx_error("SSH 参数无效，需提供远端主机和 Git 服务命令。");
        return SSH_FAILURE;
    }
    char line[MAX_REQUEST];
    struct nx_buffer request = {line, sizeof(line), 0, false};
    request_header(&request, &options, nonce);
    if (request.invalid) {
        nx_error("SSH 桥请求超过 8 KiB 或包含无效 UTF-8。");
        return SSH_FAILURE;
    }
    int fd = nx_stream_socket(AF_INET);
    struct sockaddr_in address = {
        .sin_family = AF_INET,
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
        .sin_port = htons((uint16_t)bridge_port),
    };
    if (fd < 0 || connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        nx_error("无法连接本机 SSH 桥。");
        return SSH_FAILURE;
    }
    if (!nx_send_header(fd, &request)) {
        nx_error("无法发送 SSH 桥请求。");
        return SSH_FAILURE;
    }
    if (!nx_accept_reply(fd)) {
        return SSH_FAILURE;
    }
    return nx_relay(fd, SSH_FAILURE, false);
}
