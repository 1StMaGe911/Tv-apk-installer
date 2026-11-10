#include <jni.h>

#include <openssl/curve25519.h>
#include <openssl/mem.h>

#include <cstdint>
#include <vector>

namespace {

struct NativeContext {
    SPAKE2_CTX* context = nullptr;
    bool message_generated = false;
    bool message_processed = false;

    ~NativeContext() {
        if (context != nullptr) {
            SPAKE2_CTX_free(context);
            context = nullptr;
        }
    }
};

void Throw(JNIEnv* env, const char* class_name, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(class_name);
    if (type != nullptr) env->ThrowNew(type, message);
}

bool ReadBytes(JNIEnv* env, jbyteArray input, std::vector<uint8_t>* output) {
    if (input == nullptr) return false;
    const jsize length = env->GetArrayLength(input);
    if (length < 0) return false;
    output->resize(static_cast<size_t>(length));
    if (length != 0) {
        env->GetByteArrayRegion(input, 0, length,
                                reinterpret_cast<jbyte*>(output->data()));
    }
    return !env->ExceptionCheck();
}

NativeContext* FromHandle(jlong handle) {
    return reinterpret_cast<NativeContext*>(static_cast<intptr_t>(handle));
}

jbyteArray ToJavaBytes(JNIEnv* env, const uint8_t* bytes, size_t length) {
    if (length > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result != nullptr && length != 0) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte*>(bytes));
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_flyfish233_crypto_spake2_Spake2Context_nativeCreate(
        JNIEnv* env, jclass, jint role, jbyteArray my_name, jbyteArray their_name) {
    if (role != 0 && role != 1) {
        Throw(env, "java/lang/IllegalArgumentException", "Unknown SPAKE2 role");
        return 0;
    }

    std::vector<uint8_t> my_name_bytes;
    std::vector<uint8_t> their_name_bytes;
    if (!ReadBytes(env, my_name, &my_name_bytes) ||
        !ReadBytes(env, their_name, &their_name_bytes) ||
        my_name_bytes.empty() || their_name_bytes.empty() ||
        my_name_bytes.size() > 4096 || their_name_bytes.size() > 4096) {
        Throw(env, "java/lang/IllegalArgumentException", "Invalid SPAKE2 participant names");
        return 0;
    }

    auto* native = new NativeContext();
    const spake2_role_t native_role =
            role == 0 ? spake2_role_alice : spake2_role_bob;
    native->context = SPAKE2_CTX_new(
            native_role,
            my_name_bytes.data(), my_name_bytes.size(),
            their_name_bytes.data(), their_name_bytes.size());

    OPENSSL_cleanse(my_name_bytes.data(), my_name_bytes.size());
    OPENSSL_cleanse(their_name_bytes.data(), their_name_bytes.size());

    if (native->context == nullptr) {
        delete native;
        Throw(env, "java/lang/IllegalStateException", "Unable to create SPAKE2 context");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(native));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_flyfish233_crypto_spake2_Spake2Context_nativeGenerateMessage(
        JNIEnv* env, jclass, jlong handle, jbyteArray password) {
    NativeContext* native = FromHandle(handle);
    if (native == nullptr || native->context == nullptr ||
        native->message_generated || native->message_processed) {
        Throw(env, "java/lang/IllegalStateException", "Invalid SPAKE2 context state");
        return nullptr;
    }

    std::vector<uint8_t> password_bytes;
    if (!ReadBytes(env, password, &password_bytes) ||
        password_bytes.empty() || password_bytes.size() > 65536) {
        if (!password_bytes.empty()) OPENSSL_cleanse(password_bytes.data(), password_bytes.size());
        Throw(env, "java/lang/IllegalArgumentException", "Invalid SPAKE2 password material");
        return nullptr;
    }

    uint8_t message[SPAKE2_MAX_MSG_SIZE] = {};
    size_t message_length = 0;
    const int ok = SPAKE2_generate_msg(
            native->context, message, &message_length, sizeof(message),
            password_bytes.data(), password_bytes.size());
    OPENSSL_cleanse(password_bytes.data(), password_bytes.size());

    if (ok != 1 || message_length == 0 || message_length > sizeof(message)) {
        OPENSSL_cleanse(message, sizeof(message));
        Throw(env, "java/lang/IllegalStateException", "SPAKE2 message generation failed");
        return nullptr;
    }

    native->message_generated = true;
    jbyteArray result = ToJavaBytes(env, message, message_length);
    OPENSSL_cleanse(message, sizeof(message));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_flyfish233_crypto_spake2_Spake2Context_nativeProcessMessage(
        JNIEnv* env, jclass, jlong handle, jbyteArray peer_message) {
    NativeContext* native = FromHandle(handle);
    if (native == nullptr || native->context == nullptr ||
        !native->message_generated || native->message_processed) {
        Throw(env, "java/lang/IllegalStateException", "Invalid SPAKE2 context state");
        return nullptr;
    }

    std::vector<uint8_t> peer_bytes;
    if (!ReadBytes(env, peer_message, &peer_bytes) ||
        peer_bytes.empty() || peer_bytes.size() > SPAKE2_MAX_MSG_SIZE) {
        Throw(env, "java/lang/IllegalArgumentException", "Invalid peer SPAKE2 message");
        return nullptr;
    }

    native->message_processed = true;
    uint8_t key[SPAKE2_MAX_KEY_SIZE] = {};
    size_t key_length = 0;
    const int ok = SPAKE2_process_msg(
            native->context, key, &key_length, sizeof(key),
            peer_bytes.data(), peer_bytes.size());
    OPENSSL_cleanse(peer_bytes.data(), peer_bytes.size());

    if (ok != 1 || key_length == 0 || key_length > sizeof(key)) {
        OPENSSL_cleanse(key, sizeof(key));
        return nullptr;
    }

    jbyteArray result = ToJavaBytes(env, key, key_length);
    OPENSSL_cleanse(key, sizeof(key));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_flyfish233_crypto_spake2_Spake2Context_nativeDestroy(
        JNIEnv*, jclass, jlong handle) {
    delete FromHandle(handle);
}
