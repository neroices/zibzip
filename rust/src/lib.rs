#![allow(clippy::not_unsafe_ptr_arg_deref, clippy::too_many_arguments)]
pub mod archive;
pub mod file_ops;

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObject, JObjectArray, JString, JValue};
use jni::strings::JNIString;
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jobject, jobjectArray, jsize};
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering as AtomicOrdering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

const THROTTLE_MS: u64 = 50;

static NEXT_TOKEN_ID: AtomicI64 = AtomicI64::new(1);

fn token_registry() -> &'static Mutex<HashMap<jlong, Arc<AtomicBool>>> {
    static REGISTRY: OnceLock<Mutex<HashMap<jlong, Arc<AtomicBool>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn get_cancel_token(token: jlong) -> Option<Arc<AtomicBool>> {
    if token == 0 {
        return None;
    }
    token_registry().lock().ok()?.get(&token).cloned()
}

fn throw_io_exception(env: &mut Env, msg: impl AsRef<str>) {
    let _ = env.throw_new(
        jni_str!("java/io/IOException"),
        JNIString::new(msg.as_ref()),
    );
}

fn throw_generic_exception(env: &mut Env, msg: impl AsRef<str>) {
    let _ = env.throw_new(
        jni_str!("java/lang/Exception"),
        JNIString::new(msg.as_ref()),
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeCreateCancelToken(
    _env: EnvUnowned,
    _class: JClass,
) -> jlong {
    let id = NEXT_TOKEN_ID.fetch_add(1, AtomicOrdering::Relaxed);
    if let Ok(mut reg) = token_registry().lock() {
        reg.insert(id, Arc::new(AtomicBool::new(false)));
    }
    id
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeCancelOperation(
    _env: EnvUnowned,
    _class: JClass,
    token: jlong,
) {
    if let Ok(reg) = token_registry().lock()
        && let Some(tok) = reg.get(&token) {
            tok.store(true, AtomicOrdering::Relaxed);
        }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeFreeCancelToken(
    _env: EnvUnowned,
    _class: JClass,
    token: jlong,
) {
    if let Ok(mut reg) = token_registry().lock() {
        reg.remove(&token);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeListDirectory<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    show_hidden: jboolean,
    sort_field: jint,
    ascending: jboolean,
    folders_first: jboolean,
) -> jobjectArray {
    env.with_env(|env| -> Result<jobjectArray, jni::errors::Error> {
        let dir_path_str: String = match path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        let items = match file_ops::list_directory(
            Path::new(&dir_path_str),
            show_hidden != JNI_FALSE,
            sort_field,
            ascending != JNI_FALSE,
            folders_first != JNI_FALSE,
        ) {
            Ok(it) => it,
            Err(e) => {
                throw_io_exception(env, e.to_string());
                return Ok(std::ptr::null_mut());
            }
        };

        Ok(build_file_item_array(env, items))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeSearchFiles<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    root_path: JString<'local>,
    query: JString<'local>,
    extensions: JObjectArray<'local, JString<'local>>,
    show_hidden: jboolean,
    limit: jint,
    cancel_token: jlong,
) -> jobjectArray {
    env.with_env(|env| -> Result<jobjectArray, jni::errors::Error> {
        let root_str: String = match root_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(std::ptr::null_mut()),
        };
        let query_str: String = match query.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        let exts: Vec<String> = extract_string_array(env, &extensions).unwrap_or_default();

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());

        let items = match file_ops::search_files(
            Path::new(&root_str),
            &query_str,
            &exts,
            show_hidden != JNI_FALSE,
            limit.max(1) as usize,
            cancel_ptr,
        ) {
            Ok(it) => it,
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = (!cancel_ptr.is_null()
                    && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) })
                    || msg.contains("cancelled");
                if is_cancelled {
                    Vec::new()
                } else {
                    if !env.exception_check() {
                        throw_io_exception(env, msg);
                    }
                    return Ok(std::ptr::null_mut());
                }
            }
        };

        Ok(build_file_item_array(env, items))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeListArchiveEntries<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    archive_path: JString<'local>,
    password: JString<'local>,
) -> jobjectArray {
    env.with_env(|env| -> Result<jobjectArray, jni::errors::Error> {
        let archive_path_str: String = match archive_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        let pw_str = if !password.is_null() {
            password.try_to_string(env).ok()
        } else {
            None
        };

        let entries = match archive::list_entries(Path::new(&archive_path_str), pw_str.as_deref()) {
            Ok(e) => e,
            Err(err) => {
                throw_generic_exception(env, err.to_string());
                return Ok(std::ptr::null_mut());
            }
        };

        let entry_class =
            match env.find_class(jni_str!("dev/neroices/zibzip/domain/ArchiveEntryItem")) {
                Ok(c) => c,
                Err(_) => return Ok(std::ptr::null_mut()),
            };

        let ctor = match env.get_method_id(
            &entry_class,
            jni_str!("<init>"),
            jni_sig!("(Ljava/lang/String;ZJJJZ)V"),
        ) {
            Ok(m) => m,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        let arr = match env.new_object_array(entries.len() as jsize, &entry_class, JObject::null())
        {
            Ok(a) => a,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        for (i, entry) in entries.into_iter().enumerate() {
            let path_jstr = match env.new_string(&entry.path) {
                Ok(s) => s,
                Err(_) => continue,
            };

            let args = [
                JValue::Object(path_jstr.as_ref()).as_jni(),
                JValue::Bool(if entry.is_directory {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                })
                .as_jni(),
                JValue::Long(entry.size).as_jni(),
                JValue::Long(entry.compressed_size).as_jni(),
                JValue::Long(entry.last_modified).as_jni(),
                JValue::Bool(if entry.is_encrypted {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                })
                .as_jni(),
            ];

            let obj = match unsafe { env.new_object_unchecked(&entry_class, ctor, &args) } {
                Ok(o) => o,
                Err(_) => continue,
            };

            let _ = arr.set_element(env, i, &obj);
            env.delete_local_ref(path_jstr);
            env.delete_local_ref(obj);
        }

        Ok(arr.into_raw())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeExtractArchive<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    archive_path: JString<'local>,
    dest_dir_path: JString<'local>,
    selection: JObjectArray<'local, JString<'local>>,
    base_path: JString<'local>,
    password: JString<'local>,
    conflict_policy: jint,
    cancel_token: jlong,
    progress_listener: JObject<'local>,
) -> jint {
    env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let archive_str: String = match archive_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let dest_str: String = match dest_dir_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let base_str: String = if !base_path.is_null() {
            base_path.try_to_string(env).unwrap_or_default()
        } else {
            String::new()
        };
        let pw_str = if !password.is_null() {
            password.try_to_string(env).ok()
        } else {
            None
        };

        let sel_vec: Option<Vec<String>> = if !selection.is_null() {
            Some(extract_string_array(env, &selection).unwrap_or_default())
        } else {
            None
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());
        let is_token_cancelled = || -> bool {
            !cancel_ptr.is_null() && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) }
        };
        let mut tracker = JniProgressTracker::new(env, &progress_listener);

        let res = archive::extract_archive(
            Path::new(&archive_str),
            Path::new(&dest_str),
            sel_vec.as_deref(),
            &base_str,
            pw_str.as_deref(),
            conflict_policy,
            cancel_ptr,
            |done, total, cur| tracker.report(done, total, cur, false),
        );

        if res.is_ok() && !is_token_cancelled() {
            let _ = tracker.flush();
        }

        match res {
            Ok(n) => {
                if is_token_cancelled() {
                    Ok(-1)
                } else {
                    Ok(n)
                }
            }
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = is_token_cancelled() || msg.contains("cancelled");
                if !tracker.env.exception_check() && !is_cancelled {
                    throw_generic_exception(tracker.env, msg);
                }
                Ok(-1)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeCreateArchive<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source_paths: JObjectArray<'local, JString<'local>>,
    output_path: JString<'local>,
    format: JString<'local>,
    level: jint,
    password: JString<'local>,
    cancel_token: jlong,
    progress_listener: JObject<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, jni::errors::Error> {
        let sources = match extract_string_array(env, &source_paths) {
            Ok(s) => s.into_iter().map(PathBuf::from).collect::<Vec<_>>(),
            Err(_) => return Ok(-1),
        };
        let out_str: String = match output_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let fmt_str: String = match format.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let pw_str = if !password.is_null() {
            password.try_to_string(env).ok()
        } else {
            None
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());
        let is_token_cancelled = || -> bool {
            !cancel_ptr.is_null() && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) }
        };
        let mut tracker = JniProgressTracker::new(env, &progress_listener);

        let res = archive::create_archive(
            &sources,
            Path::new(&out_str),
            &fmt_str,
            level,
            pw_str.as_deref(),
            cancel_ptr,
            |done, total, cur| tracker.report(done, total, cur, false),
        );

        if res.is_ok() && !is_token_cancelled() {
            let _ = tracker.flush();
        }

        match res {
            Ok(bytes) => {
                if is_token_cancelled() {
                    Ok(-1)
                } else {
                    Ok(bytes)
                }
            }
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = is_token_cancelled() || msg.contains("cancelled");
                if !tracker.env.exception_check() && !is_cancelled {
                    throw_generic_exception(tracker.env, msg);
                }
                Ok(-1)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeRemoveZipEntries<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    archive_path: JString<'local>,
    paths_to_remove: JObjectArray<'local, JString<'local>>,
    cancel_token: jlong,
    progress_listener: JObject<'local>,
) -> jint {
    env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let archive_str: String = match archive_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let to_remove = match extract_string_array(env, &paths_to_remove) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());
        let is_token_cancelled = || -> bool {
            !cancel_ptr.is_null() && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) }
        };
        let mut tracker = JniProgressTracker::new(env, &progress_listener);

        let res = archive::remove_zip_entries(
            Path::new(&archive_str),
            &to_remove,
            cancel_ptr,
            |done, total, cur| tracker.report(done, total, cur, false),
        );

        if res.is_ok() && !is_token_cancelled() {
            let _ = tracker.flush();
        }

        match res {
            Ok(n) => {
                if is_token_cancelled() {
                    Ok(-1)
                } else {
                    Ok(n)
                }
            }
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = is_token_cancelled() || msg.contains("cancelled");
                if !tracker.env.exception_check() && !is_cancelled {
                    throw_generic_exception(tracker.env, msg);
                }
                Ok(-1)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeAddZipEntries<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    archive_path: JString<'local>,
    inner_dir: JString<'local>,
    source_paths: JObjectArray<'local, JString<'local>>,
    level: jint,
    password: JString<'local>,
    cancel_token: jlong,
    progress_listener: JObject<'local>,
) -> jint {
    env.with_env(|env| -> Result<jint, jni::errors::Error> {
        let archive_str: String = match archive_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let inner_str: String = match inner_dir.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };
        let sources = match extract_string_array(env, &source_paths) {
            Ok(s) => s.into_iter().map(PathBuf::from).collect::<Vec<_>>(),
            Err(_) => return Ok(-1),
        };
        let pw_str = if !password.is_null() {
            password.try_to_string(env).ok()
        } else {
            None
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());
        let is_token_cancelled = || -> bool {
            !cancel_ptr.is_null() && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) }
        };
        let mut tracker = JniProgressTracker::new(env, &progress_listener);

        let res = archive::add_zip_entries(
            Path::new(&archive_str),
            &inner_str,
            &sources,
            level,
            pw_str.as_deref(),
            cancel_ptr,
            |done, total, cur| tracker.report(done, total, cur, false),
        );

        if res.is_ok() && !is_token_cancelled() {
            let _ = tracker.flush();
        }

        match res {
            Ok(n) => {
                if is_token_cancelled() {
                    Ok(-1)
                } else {
                    Ok(n)
                }
            }
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = is_token_cancelled() || msg.contains("cancelled");
                if !tracker.env.exception_check() && !is_cancelled {
                    throw_generic_exception(tracker.env, msg);
                }
                Ok(-1)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeCopyFiles<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source_paths: JObjectArray<'local, JString<'local>>,
    dest_dir_path: JString<'local>,
    cancel_token: jlong,
    progress_listener: JObject<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, jni::errors::Error> {
        let sources = match extract_string_array(env, &source_paths) {
            Ok(s) => s.into_iter().map(PathBuf::from).collect::<Vec<_>>(),
            Err(_) => return Ok(-1),
        };
        let dest_str: String = match dest_dir_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(-1),
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());
        let is_token_cancelled = || -> bool {
            !cancel_ptr.is_null() && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) }
        };
        let mut tracker = JniProgressTracker::new(env, &progress_listener);

        let res = file_ops::copy_files(
            &sources,
            Path::new(&dest_str),
            cancel_ptr,
            |done, total, cur| tracker.report(done, total, cur, false),
        );

        if res.is_ok() && !is_token_cancelled() {
            let _ = tracker.flush();
        }

        match res {
            Ok(bytes) => {
                if is_token_cancelled() {
                    Ok(-1)
                } else {
                    Ok(bytes)
                }
            }
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = is_token_cancelled()
                    || msg.contains("cancelled")
                    || msg.contains("Interrupted");
                if !tracker.env.exception_check() && !is_cancelled {
                    throw_io_exception(tracker.env, msg);
                }
                Ok(-1)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeDeleteFiles<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    paths: JObjectArray<'local, JString<'local>>,
    cancel_token: jlong,
    progress_listener: JObject<'local>,
) -> jlong {
    env.with_env(|env| -> Result<jlong, jni::errors::Error> {
        let path_list = match extract_string_array(env, &paths) {
            Ok(s) => s.into_iter().map(PathBuf::from).collect::<Vec<_>>(),
            Err(_) => return Ok(-1),
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());
        let is_token_cancelled = || -> bool {
            !cancel_ptr.is_null() && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) }
        };
        let mut tracker = JniProgressTracker::new(env, &progress_listener);

        let res = file_ops::delete_files(&path_list, cancel_ptr, |done, total, cur| {
            tracker.report(done, total, cur, false)
        });

        if res.is_ok() && !is_token_cancelled() {
            let _ = tracker.flush();
        }

        match res {
            Ok(n) => {
                if is_token_cancelled() {
                    Ok(-1)
                } else {
                    Ok(n)
                }
            }
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = is_token_cancelled()
                    || msg.contains("cancelled")
                    || msg.contains("Interrupted");
                if !tracker.env.exception_check() && !is_cancelled {
                    throw_io_exception(tracker.env, msg);
                }
                Ok(-1)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_neroices_zibzip_data_NativeArchiveBridge_nativeFolderStats<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    dir_path: JString<'local>,
    cancel_token: jlong,
) -> jobject {
    env.with_env(|env| -> Result<jobject, jni::errors::Error> {
        let dir_str: String = match dir_path.try_to_string(env) {
            Ok(s) => s,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        let cancel_arc = get_cancel_token(cancel_token);
        let cancel_ptr = cancel_arc
            .as_deref()
            .map(|a| a as *const AtomicBool)
            .unwrap_or(std::ptr::null());

        let stats = match file_ops::folder_stats(Path::new(&dir_str), cancel_ptr) {
            Ok(s) => s,
            Err(e) => {
                let msg = e.to_string();
                let is_cancelled = (!cancel_ptr.is_null()
                    && unsafe { (*cancel_ptr).load(AtomicOrdering::Relaxed) })
                    || msg.contains("cancelled");
                if is_cancelled {
                    [0i64, 0i64, 0i64]
                } else {
                    if !env.exception_check() {
                        throw_io_exception(env, msg);
                    }
                    return Ok(std::ptr::null_mut());
                }
            }
        };

        let arr = match env.new_long_array(3) {
            Ok(a) => a,
            Err(_) => return Ok(std::ptr::null_mut()),
        };

        let _ = arr.set_region(env, 0, &stats);
        Ok(arr.into_raw() as jobject)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

struct JniProgressTracker<'a, 'b> {
    env: &'b mut Env<'a>,
    listener: &'b JObject<'a>,
    method_id: Option<jni::objects::JMethodID>,
    last_emit: Instant,
    last_done: i64,
    last_total: i64,
    last_cur: String,
}

impl<'a, 'b> JniProgressTracker<'a, 'b> {
    fn new(env: &'b mut Env<'a>, listener: &'b JObject<'a>) -> Self {
        let method_id = if !listener.is_null() {
            if let Ok(class) = env.get_object_class(listener) {
                let mid = env.get_method_id(
                    &class,
                    jni_str!("onProgress"),
                    jni_sig!("(JJLjava/lang/String;)V"),
                );
                if env.exception_check() {
                    env.exception_clear();
                }
                mid.ok()
            } else {
                if env.exception_check() {
                    env.exception_clear();
                }
                None
            }
        } else {
            None
        };

        Self {
            env,
            listener,
            method_id,
            last_emit: Instant::now() - Duration::from_millis(THROTTLE_MS),
            last_done: 0,
            last_total: 0,
            last_cur: String::new(),
        }
    }

    fn report(&mut self, done: i64, total: i64, current: &str, force: bool) -> Result<(), ()> {
        let Some(mid) = self.method_id else {
            return Ok(());
        };
        if self.listener.is_null() {
            return Ok(());
        }

        if self.env.exception_check() {
            self.env.exception_clear();
            return Err(());
        }

        self.last_done = done;
        self.last_total = total;
        self.last_cur.clear();
        self.last_cur.push_str(current);

        let now = Instant::now();
        if !force && now.duration_since(self.last_emit) < Duration::from_millis(THROTTLE_MS) {
            return Ok(());
        }
        self.last_emit = now;

        let cur_str = match self.env.new_string(current) {
            Ok(s) => s,
            Err(_) => {
                self.env.exception_clear();
                return Err(());
            }
        };

        let args = [
            JValue::Long(done).as_jni(),
            JValue::Long(total).as_jni(),
            JValue::Object(cur_str.as_ref()).as_jni(),
        ];

        unsafe {
            let res = self.env.call_method_unchecked(
                self.listener,
                mid,
                jni::signature::ReturnType::Primitive(jni::signature::Primitive::Void),
                &args,
            );
            self.env.delete_local_ref(cur_str);
            if res.is_err() || self.env.exception_check() {
                self.env.exception_clear();
                return Err(());
            }
        }

        Ok(())
    }

    fn flush(&mut self) -> Result<(), ()> {
        if self.env.exception_check() {
            self.env.exception_clear();
            return Err(());
        }
        let cur = self.last_cur.clone();
        self.report(self.last_done, self.last_total, &cur, true)
    }
}

fn extract_string_array<'local>(
    env: &mut Env<'local>,
    arr: &JObjectArray<'local, JString<'local>>,
) -> Result<Vec<String>, ()> {
    if arr.is_null() {
        return Ok(Vec::new());
    }
    let len = arr.len(env).map_err(|_| ())?;
    let mut vec = Vec::with_capacity(len);
    for i in 0..len {
        let jstr = arr.get_element(env, i).map_err(|_| ())?;
        if !jstr.is_null() {
            let val = jstr.try_to_string(env).map_err(|_| ())?;
            vec.push(val);
            env.delete_local_ref(jstr);
        }
    }
    Ok(vec)
}

fn build_file_item_array<'local>(
    env: &mut Env<'local>,
    items: Vec<file_ops::DirEntryItem>,
) -> jobjectArray {
    let item_class = match env.find_class(jni_str!("dev/neroices/zibzip/domain/FileItem")) {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };

    let ctor = match env.get_method_id(
        &item_class,
        jni_str!("<init>"),
        jni_sig!("(Ljava/lang/String;Ljava/lang/String;ZJJZ)V"),
    ) {
        Ok(m) => m,
        Err(_) => return std::ptr::null_mut(),
    };

    let arr = match env.new_object_array(items.len() as jsize, &item_class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };

    for (i, item) in items.into_iter().enumerate() {
        let path_jstr = match env.new_string(&item.path) {
            Ok(s) => s,
            Err(_) => continue,
        };
        let name_jstr = match env.new_string(&item.name) {
            Ok(s) => s,
            Err(_) => continue,
        };

        let args = [
            JValue::Object(path_jstr.as_ref()).as_jni(),
            JValue::Object(name_jstr.as_ref()).as_jni(),
            JValue::Bool(if item.is_directory {
                JNI_TRUE
            } else {
                JNI_FALSE
            })
            .as_jni(),
            JValue::Long(item.size).as_jni(),
            JValue::Long(item.last_modified).as_jni(),
            JValue::Bool(if item.is_hidden { JNI_TRUE } else { JNI_FALSE }).as_jni(),
        ];

        let obj = match unsafe { env.new_object_unchecked(&item_class, ctor, &args) } {
            Ok(o) => o,
            Err(_) => continue,
        };

        let _ = arr.set_element(env, i, &obj);
        env.delete_local_ref(path_jstr);
        env.delete_local_ref(name_jstr);
        env.delete_local_ref(obj);
    }

    arr.into_raw()
}
