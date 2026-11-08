//! Small JNI bridge directly to the pinned libtelio core. It deliberately avoids
//! the upstream FFI protect callback, which logs errors but does not propagate them.
//! Our Protector returns io::Error when Android cannot protect AND bind a socket.
//! NepTUN's callback discards that error, so failed sockets are atomically replaced
//! with non-network AF_UNIX sockets before returning. They cannot fall back to cellular.

use jni::{objects::{GlobalRef, JObject, JString, JValue}, sys::{jint, jlong, jstring}, JNIEnv, JavaVM};
use std::{collections::HashMap, io, os::fd::{FromRawFd, OwnedFd}, ptr, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicI64, Ordering}}, time::Duration};
use telio_core::{device::{Device, DeviceConfig}, crypto::SecretKey, telio_model::{config::Config, event::Event, features::Features}, telio_sockets::{protector::Protector, native::NativeSocket}, telio_wg::AdapterType};

struct Notifier { vm: JavaVM, callback: GlobalRef, states: Mutex<HashMap<String, String>> }
impl Notifier {
    fn changed(&self) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            let _ = env.call_method(self.callback.as_obj(), "changed", "()V", &[]);
            if env.exception_check().unwrap_or(false) { let _ = env.exception_clear(); }
        }
    }
    fn state(&self, key: String, value: String) {
        let changed = self.states.lock().map(|mut states| {
            if states.get(&key) == Some(&value) { false } else { states.insert(key, value); true }
        }).unwrap_or(false);
        if changed { self.changed(); }
    }
}

struct AndroidProtector { vm: JavaVM, callback: GlobalRef, failed: Arc<AtomicBool>, notifier: Arc<Notifier> }
impl Protector for AndroidProtector {
    fn make_external(&self, socket: NativeSocket) -> io::Result<()> {
        let result = (|| {
            let mut env = self.vm.attach_current_thread().map_err(|_| io::Error::other("JNI attach failed"))?;
            let call = env.call_method(self.callback.as_obj(), "protectAndBind", "(I)Z", &[JValue::Int(socket)]);
            if env.exception_check().unwrap_or(true) {
                let _ = env.exception_clear();
                return Err(io::Error::other("Android socket binding threw"));
            }
            let ok = call.and_then(|value| value.z()).unwrap_or(false);
            if ok { Ok(()) } else { Err(io::Error::other("Android socket protection/binding failed")) }
        })();
        if result.is_err() {
            quarantine(socket);
            self.failed.store(true, Ordering::Release);
            self.notifier.changed();
        }
        result
    }
    fn make_internal(&self, _: NativeSocket) -> io::Result<()> { Ok(()) }
    fn clean(&self, _: NativeSocket) {}
    fn set_fwmark(&self, _: u32) {}
    fn set_tunnel_interface(&self, _: u64) {}
    fn set_ext_if_filter(&self, _: &[String]) {}
}

/// Preserve descriptor ownership/number without leaving an unbound network socket
/// usable by upstream callbacks which discard protection errors. Never close an
/// upstream-owned fd directly (that would introduce fd-reuse/double-close races).
fn quarantine(socket: NativeSocket) {
    unsafe {
        let replacement = libc::socket(libc::AF_UNIX, libc::SOCK_DGRAM | libc::SOCK_NONBLOCK | libc::SOCK_CLOEXEC, 0);
        if replacement < 0 { std::process::abort(); } // Resource exhaustion: fail closed.
        let result = libc::dup2(replacement, socket);
        libc::close(replacement);
        if result < 0 { std::process::abort(); }
    }
}

struct Session { device: Device, failed: Arc<AtomicBool> }
static SESSIONS: OnceLock<Mutex<HashMap<i64, Session>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn sessions() -> &'static Mutex<HashMap<i64, Session>> { SESSIONS.get_or_init(|| Mutex::new(HashMap::new())) }
fn fail(env: &mut JNIEnv, message: &str) { let _ = env.throw_new("java/lang/IllegalStateException", message); }

// Never format upstream error payloads: they may contain keys, maps or peer data.
// Static categories/OS errno identify startup failures without exposing credentials.
fn device_diagnostic(stage: &str, error: &telio_core::device::Error) -> String {
    use telio_core::device::ErrorVariant as D;
    let category = match error.variant() {
        D::Adapter(inner) => match inner.variant() {
            telio_core::telio_wg::ErrorVariant::NepTUN(inner) => format!("NepTUN category {:?}", std::mem::discriminant(inner)),
            telio_core::telio_wg::ErrorVariant::IoError(io) => format!("adapter I/O errno {:?}", io.raw_os_error()),
            other => format!("adapter category {:?}", std::mem::discriminant(other)),
        },
        D::AsyncRuntime(io) => format!("runtime/network I/O errno {:?}", io.raw_os_error()),
        D::BadPrivateKey | D::BadPublicKey => "local identity/key mismatch".to_owned(),
        D::NoMeshnetIP => "missing Meshnet address".to_owned(),
        D::FirewallError(_) => "firewall setup".to_owned(),
        other => format!("device category {:?}", std::mem::discriminant(other)),
    };
    format!("{stage}: {category}")
}

#[no_mangle]
pub extern "system" fn Java_com_mybm_meshhome_NativeTelio_apiVersion(_: JNIEnv, _: JObject) -> jint { 3 }

#[no_mangle]
pub extern "system" fn Java_com_mybm_meshhome_NativeTelio_start(
    mut env: JNIEnv, _: JObject, key: JString, map: JString, fd: jint, callback: JObject, events: JObject,
) -> jlong {
    // Adopt immediately. Kotlin must not close this duplicate, even if parsing/start fails.
    let tun = unsafe { OwnedFd::from_raw_fd(fd) };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<i64, String> {
        let key: String = env.get_string(&key).map_err(|_| "reading local key")?.into();
        let map: String = env.get_string(&map).map_err(|_| "reading peer map")?.into();
        let key: SecretKey = key.parse().map_err(|_| "decoding local key")?;
        let (config, rejected) = Config::new_from_str(&map).map_err(|_| "parsing peer map")?;
        if rejected > 0 { return Err("validating peer map entries".to_owned()); }
        let failed = Arc::new(AtomicBool::new(false));
        let notifier = Arc::new(Notifier { vm: env.get_java_vm().map_err(|_| "initializing JVM callback")?, callback: env.new_global_ref(events).map_err(|_| "retaining event callback")?, states: Mutex::new(HashMap::new()) });
        let protector = AndroidProtector { vm: env.get_java_vm().map_err(|_| "initializing socket callback")?, callback: env.new_global_ref(callback).map_err(|_| "retaining socket callback")?, failed: failed.clone(), notifier: notifier.clone() };
        // Built-in Mozilla roots avoid requiring Android certificate-store initialization.
        // No telemetry, analytics, exit routing or UPnP is enabled.
        let mut features: Features = serde_json::from_str(r#"{
            "nurse":null,"lana":null,"ipv6":true,"firewall":null,
            "direct":null,
            "derp":{"use_built_in_root_certificates":true},
            "hide_user_data":true,"flush_events_on_stop_timeout_seconds":1
        }"#).map_err(|_| "parsing native feature settings")?;
        // Use upstream's recommended battery preset rather than disabling keepalives
        // or inventing NAT/relay timeouts. Preserve our TLS/privacy/relay-only choices.
        let battery = Arc::new(telio_core::defaults_builder::FeaturesDefaultsBuilder::new())
            .enable_battery_saving_defaults().build();
        features.wireguard.persistent_keepalive = battery.wireguard.persistent_keepalive;
        features.derp = Some(telio_core::telio_model::features::FeatureDerp {
            use_built_in_root_certificates: true, ..battery.derp.unwrap_or_default()
        });
        let errors = failed.clone();
        let mut device = Device::new(features, move |event: Box<Event>| {
            match *event {
                Event::Error { body } => {
                    if matches!(body.level, telio_core::telio_model::event::ErrorLevel::Critical | telio_core::telio_model::event::ErrorLevel::Severe) {
                        errors.store(true, Ordering::Release); notifier.changed();
                    }
                }
                Event::Node { body } => notifier.state(format!("node:{}", body.identifier), format!("{:?}/{:?}", body.state, body.path)),
                Event::Relay { body } => notifier.state(format!("relay:{}", body.hostname), format!("{:?}", body.conn_state)),
            }
        }, Some(Arc::new(protector))).map_err(|e| device_diagnostic("creating runtime", &e))?;
        device.start(DeviceConfig { private_key: key, adapter: AdapterType::NepTUN, fwmark: None, name: None,
            tun: Some(tun), ext_if_filter: None, mtu: None }).map_err(|e| {
                if failed.load(Ordering::Acquire) { "starting adapter: socket protection/network binding failed".to_owned() }
                else { device_diagnostic("starting adapter", &e) }
            })?;
        if let Some(ips) = &config.this.ip_addresses {
            if let Err(e) = device.set_tunnel_src_ip(ips.clone()) { device.stop(); return Err(device_diagnostic("configuring source addresses", &e)); }
        }
        if let Err(e) = device.set_config(&Some(config)) { device.stop(); return Err(device_diagnostic("applying peer map", &e)); }
        let handle = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions().lock().map_err(|_| "registering native session")?.insert(handle, Session { device, failed });
        Ok(handle)
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(reason)) => { fail(&mut env, &format!("Native Meshnet initialization failed: {reason}.")); 0 },
        Err(_) => { fail(&mut env, "Native Meshnet initialization failed: native panic (details withheld)."); 0 },
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybm_meshhome_NativeTelio_updateMap(mut env: JNIEnv, _: JObject, handle: jlong, map: JString) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<(), ()> {
        let map: String = env.get_string(&map).map_err(|_| ())?.into();
        let (config, rejected) = Config::new_from_str(&map).map_err(|_| ())?;
        if rejected > 0 { return Err(()); }
        let mut sessions = sessions().lock().map_err(|_| ())?;
        let session = sessions.get_mut(&handle).ok_or(())?;
        if session.failed.load(Ordering::Acquire) { return Err(()); }
        session.device.set_tunnel_src_ip(config.this.ip_addresses.clone().unwrap_or_default()).map_err(|_| ())?;
        session.device.set_config(&Some(config)).map_err(|_| ())
    }));
    if !matches!(result, Ok(Ok(()))) { fail(&mut env, "Meshnet peer-map update failed."); }
}

#[no_mangle]
pub extern "system" fn Java_com_mybm_meshhome_NativeTelio_status(mut env: JNIEnv, _: JObject, handle: jlong) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<String, ()> {
        let sessions = sessions().lock().map_err(|_| ())?;
        let session = sessions.get(&handle).ok_or(())?;
        if session.failed.load(Ordering::Acquire) { return Err(()); }
        let nodes = session.device.external_nodes().map_err(|_| ())?;
        // Return machine-readable status; Kotlin must not confuse engine readiness with peer reachability.
        serde_json::to_string(&nodes).map_err(|_| ())
    }));
    match result {
        Ok(Ok(value)) => env.new_string(value).map(|s| s.into_raw()).unwrap_or(ptr::null_mut()),
        _ => { fail(&mut env, "Meshnet engine or socket protection failed; session stopped."); ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mybm_meshhome_NativeTelio_stop(mut env: JNIEnv, _: JObject, handle: jlong) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Ok(mut sessions) = sessions().lock() {
            if let Some(mut session) = sessions.remove(&handle) {
                session.device.stop();
                let _ = session.device.try_shutdown_art(Duration::from_secs(2));
            }
        }
    }));
    if result.is_err() { fail(&mut env, "Native Meshnet shutdown failed."); }
}
