#include <openssl/curve25519.h>
#include <openssl/mem.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>

int main() {
    constexpr uint8_t kClientName[] = "adb pair client";
    constexpr uint8_t kServerName[] = "adb pair server";
    constexpr uint8_t kPassword[] = "ADB SPAKE2 host interoperability test";

    SPAKE2_CTX* client = SPAKE2_CTX_new(
            spake2_role_alice, kClientName, sizeof(kClientName),
            kServerName, sizeof(kServerName));
    SPAKE2_CTX* server = SPAKE2_CTX_new(
            spake2_role_bob, kServerName, sizeof(kServerName),
            kClientName, sizeof(kClientName));
    if (client == nullptr || server == nullptr) {
        SPAKE2_CTX_free(client);
        SPAKE2_CTX_free(server);
        std::fputs("Unable to create SPAKE2 contexts\n", stderr);
        return 1;
    }

    std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> client_message{};
    std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> server_message{};
    size_t client_message_length = 0;
    size_t server_message_length = 0;
    const bool generated =
            SPAKE2_generate_msg(client, client_message.data(), &client_message_length,
                                client_message.size(), kPassword, sizeof(kPassword)) == 1 &&
            SPAKE2_generate_msg(server, server_message.data(), &server_message_length,
                                server_message.size(), kPassword, sizeof(kPassword)) == 1;

    std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> client_key{};
    std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> server_key{};
    size_t client_key_length = 0;
    size_t server_key_length = 0;
    const bool processed = generated &&
            SPAKE2_process_msg(client, client_key.data(), &client_key_length,
                               client_key.size(), server_message.data(),
                               server_message_length) == 1 &&
            SPAKE2_process_msg(server, server_key.data(), &server_key_length,
                               server_key.size(), client_message.data(),
                               client_message_length) == 1;

    const bool matched = processed &&
            client_key_length == server_key_length &&
            std::memcmp(client_key.data(), server_key.data(), client_key_length) == 0;

    OPENSSL_cleanse(client_key.data(), client_key.size());
    OPENSSL_cleanse(server_key.data(), server_key.size());
    OPENSSL_cleanse(client_message.data(), client_message.size());
    OPENSSL_cleanse(server_message.data(), server_message.size());
    SPAKE2_CTX_free(client);
    SPAKE2_CTX_free(server);

    if (!matched) {
        std::fputs("SPAKE2 client/server keys did not match\n", stderr);
        return 1;
    }

    std::puts("SPAKE2 client/server interoperability passed");
    return 0;
}
