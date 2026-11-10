// Copyright (c) Android Remote VPN contributors. Licensed under MPL-2.0.
// This bridge calls the upstream OpenVPN 3 Core; it does not implement the VPN cryptography.
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <openvpn/client/dns_options.hpp>
#include <client/ovpncli.hpp>

namespace {
using namespace openvpn::ClientAPI;

class ScopedEnv {
public:
    explicit ScopedEnv(JavaVM* vm) : vm_(vm) {
        if (vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (vm_->AttachCurrentThread(reinterpret_cast<void**>(&env_), nullptr) == JNI_OK) detach_ = true;
        }
    }
    ~ScopedEnv() { if (detach_) vm_->DetachCurrentThread(); }
    JNIEnv* get() const { return env_; }
private:
    JavaVM* vm_;
    JNIEnv* env_ = nullptr;
    bool detach_ = false;
};

std::string readString(JNIEnv* env, jstring str) {
    if (!str) return {};
    const char* ptr = env->GetStringUTFChars(str, nullptr);
    if (!ptr) return {};
    std::string result(ptr);
    env->ReleaseStringUTFChars(str, ptr);
    return result;
}

class AndroidClient final : public OpenVPNClient {
public:
    AndroidClient(JavaVM* vm, JNIEnv* env, jobject target)
        : vm_(vm), target_(env->NewGlobalRef(target)) {}
    ~AndroidClient() override {
        ScopedEnv scope(vm_);
        if (auto* env = scope.get()) env->DeleteGlobalRef(target_);
    }

    // The upstream OpenVPNClient interface requires this callback.
    // For a first test, do not silently pause on connection timeout.
    bool pause_on_connection_timeout() override { return false; }

    bool socket_protect(openvpn_io::detail::socket_type fd, std::string, bool) override {
        return callBoolInt("onProtect", static_cast<jint>(fd));
    }
    bool tun_builder_new() override { return callBoolNoArgs("onBegin"); }
    bool tun_builder_set_layer(int layer) override { return layer == 3; }
    bool tun_builder_set_remote_address(const std::string&, bool) override { return true; }
    bool tun_builder_add_address(const std::string& address, int prefix,
                                 const std::string&, bool, bool) override {
        return callBoolStringInt("onAddress", address, prefix);
    }
    bool tun_builder_add_route(const std::string& address, int prefix, int, bool) override {
        return callBoolStringInt("onRoute", address, prefix);
    }
    bool tun_builder_exclude_route(const std::string& address, int prefix, int, bool) override {
        return callBoolStringInt("onExcludeRoute", address, prefix);
    }
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int) override {
        return (!ipv4 || callBoolStringInt("onRoute", "0.0.0.0", 0)) &&
               (!ipv6 || callBoolStringInt("onRoute", "::", 0));
    }
    bool tun_builder_set_dns_options(const openvpn::DnsOptions& dns) override {
        for (const auto& [priority, server] : dns.servers) {
            (void)priority;
            if (server.transport != openvpn::DnsServer::Transport::Unset &&
                server.transport != openvpn::DnsServer::Transport::Plain) return false;
            for (const auto& address : server.addresses) {
                if (address.port != 0 && address.port != 53) return false;
                if (!callBoolString("onDns", address.address)) return false;
            }
        }
        for (const auto& domain : dns.search_domains)
            if (!callBoolString("onSearchDomain", domain.domain)) return false;
        return true;
    }
    bool tun_builder_set_mtu(int mtu) override { return callBoolInt("onMtu", mtu); }
    bool tun_builder_set_session_name(const std::string&) override { return true; }
    bool tun_builder_persist() override { return false; } // new TUN per session; no stale descriptors
    int tun_builder_establish() override { return callIntNoArgs("onEstablish"); }
    void tun_builder_teardown(bool) override { callVoidNoArgs("onTeardown"); }

    void event(const Event& event) override {
        // Never forward event.info: it may contain authentication material.
        callState(event.name == "CONNECTED" ? "Połączono OpenVPN" :
                  event.name == "DISCONNECTED" ? "Rozłączono OpenVPN" :
                  event.error ? "Błąd OpenVPN: " + event.name : "OpenVPN: " + event.name);
    }
    void acc_event(const AppCustomControlMessageEvent&) override {}
    void log(const LogInfo&) override {} // avoid leaking credentials into Android logs
    void external_pki_cert_request(ExternalPKICertRequest& req) override {
        req.error = true;
        req.errorText = "Zewnętrzny magazyn certyfikatów nie jest jeszcze obsługiwany";
    }
    void external_pki_sign_request(ExternalPKISignRequest& req) override {
        req.error = true;
        req.errorText = "Zewnętrzny magazyn certyfikatów nie jest jeszcze obsługiwany";
    }
    void callState(const std::string& message) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, "onState", "(Ljava/lang/String;)V");
        if (method) {
            jstring value = env->NewStringUTF(message.c_str());
            env->CallVoidMethod(target_, method, value);
            env->DeleteLocalRef(value);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
private:
    JavaVM* vm_;
    jobject target_;
    bool callBoolNoArgs(const char* methodName) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return false;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, methodName, "()Z");
        jboolean result = method ? env->CallBooleanMethod(target_, method) : JNI_FALSE;
        if (env->ExceptionCheck()) { env->ExceptionClear(); result = JNI_FALSE; }
        env->DeleteLocalRef(cls);
        return result == JNI_TRUE;
    }
    bool callBoolInt(const char* methodName, jint n) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return false;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, methodName, "(I)Z");
        jboolean result = method ? env->CallBooleanMethod(target_, method, n) : JNI_FALSE;
        if (env->ExceptionCheck()) { env->ExceptionClear(); result = JNI_FALSE; }
        env->DeleteLocalRef(cls);
        return result == JNI_TRUE;
    }
    bool callBoolString(const char* methodName, const std::string& text) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return false;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, methodName, "(Ljava/lang/String;)Z");
        jstring value = env->NewStringUTF(text.c_str());
        jboolean result = method ? env->CallBooleanMethod(target_, method, value) : JNI_FALSE;
        if (env->ExceptionCheck()) { env->ExceptionClear(); result = JNI_FALSE; }
        env->DeleteLocalRef(value); env->DeleteLocalRef(cls);
        return result == JNI_TRUE;
    }
    bool callBoolStringInt(const char* methodName, const std::string& text, int n) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return false;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, methodName, "(Ljava/lang/String;I)Z");
        jstring value = env->NewStringUTF(text.c_str());
        jboolean result = method ? env->CallBooleanMethod(target_, method, value, static_cast<jint>(n)) : JNI_FALSE;
        if (env->ExceptionCheck()) { env->ExceptionClear(); result = JNI_FALSE; }
        env->DeleteLocalRef(value); env->DeleteLocalRef(cls);
        return result == JNI_TRUE;
    }
    int callIntNoArgs(const char* methodName) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return -1;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, methodName, "()I");
        jint result = method ? env->CallIntMethod(target_, method) : -1;
        if (env->ExceptionCheck()) { env->ExceptionClear(); result = -1; }
        env->DeleteLocalRef(cls);
        return result;
    }
    void callVoidNoArgs(const char* methodName) {
        ScopedEnv scope(vm_); JNIEnv* env = scope.get(); if (!env) return;
        jclass cls = env->GetObjectClass(target_);
        jmethodID method = env->GetMethodID(cls, methodName, "()V");
        if (method) env->CallVoidMethod(target_, method);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
    }
};

struct Engine {
    std::mutex mutex;
    std::shared_ptr<AndroidClient> client;
    JavaVM* vm = nullptr;
    jobject target = nullptr;
    ~Engine() {
        if (target && vm) {
            ScopedEnv scope(vm);
            if (scope.get()) scope.get()->DeleteGlobalRef(target);
        }
    }
};

void failToJava(JNIEnv* env, const char* text) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, text);
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_pl_remotevpn_host_NativeOpenVpn_create(JNIEnv* env, jobject object) {
    auto engine = std::make_unique<Engine>();
    env->GetJavaVM(&engine->vm);
    engine->target = env->NewGlobalRef(object);
    return reinterpret_cast<jlong>(engine.release());
}
extern "C" JNIEXPORT void JNICALL
Java_pl_remotevpn_host_NativeOpenVpn_run(JNIEnv* env, jobject, jlong handle,
                                         jstring profile, jstring username, jstring password) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine) { failToJava(env, "Brak silnika OpenVPN"); return; }
    try {
        auto client = std::make_shared<AndroidClient>(engine->vm, env, engine->target);
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            engine->client = client;
        }
        Config config;
        config.content = readString(env, profile);
        config.guiVersion = "AndroidRemoteVPN 0.5";
        config.tunPersist = false;
        config.enableLegacyAlgorithms = false;
        config.enableNonPreferredDCAlgorithms = false;
        EvalConfig evaluated = client->eval_config(config);
        if (evaluated.error) {
            client->callState("Błąd konfiguracji OpenVPN");
        } else if (!evaluated.autologin && (!username || readString(env, username).empty())) {
            client->callState("Profil wymaga loginu/hasła — interfejs logowania do dodania");
        } else {
            if (!evaluated.autologin) {
                ProvideCreds creds;
                creds.username = readString(env, username);
                creds.password = readString(env, password);
                Status result = client->provide_creds(creds);
                if (result.error) { client->callState("Błąd logowania OpenVPN"); }
                else { Status connected = client->connect(); if (connected.error) client->callState("Błąd połączenia OpenVPN"); }
            } else {
                Status connected = client->connect();
                if (connected.error) client->callState("Błąd połączenia OpenVPN");
            }
        }
    } catch (const std::exception&) {
        failToJava(env, "Natywny silnik OpenVPN zgłosił błąd");
    }
    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->client.reset();
}
extern "C" JNIEXPORT void JNICALL
Java_pl_remotevpn_host_NativeOpenVpn_stop(JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine) return;
    std::shared_ptr<AndroidClient> client;
    { std::lock_guard<std::mutex> lock(engine->mutex); client = engine->client; }
    if (client) client->stop();
}
extern "C" JNIEXPORT void JNICALL
Java_pl_remotevpn_host_NativeOpenVpn_destroy(JNIEnv*, jobject, jlong handle) {
    // Caller must join the worker before destroying the Engine.
    delete reinterpret_cast<Engine*>(handle);
}
