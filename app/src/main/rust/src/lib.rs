use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jint, jlong};
use crc32fast::Hasher;
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_calculateCrc32(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jint {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let mut hasher = Hasher::new();
    hasher.update(&bytes);
    hasher.finalize() as i32
}

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_verifyCrc32(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    expected_crc: jint,
) -> jboolean {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let mut hasher = Hasher::new();
    hasher.update(&bytes);
    let calculated = hasher.finalize() as i32;
    if calculated == expected_crc { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_cryptChaCha20(
    mut env: JNIEnv,
    _class: JClass,
    key: JByteArray,
    nonce: JByteArray,
    data: JByteArray,
) -> JByteArray {
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(b) => b,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };
    let nonce_bytes = match env.convert_byte_array(&nonce) {
        Ok(b) => b,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };
    let mut data_bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };

    if key_bytes.len() == 32 && nonce_bytes.len() == 12 {
        let key_arr = chacha20::Key::from_slice(&key_bytes);
        let nonce_arr = chacha20::Nonce::from_slice(&nonce_bytes);
        let mut cipher = ChaCha20::new(key_arr, nonce_arr);
        cipher.apply_keystream(&mut data_bytes);
    }

    match env.byte_array_from_slice(&data_bytes) {
        Ok(arr) => arr,
        Err(_) => env.new_byte_array(0).unwrap(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_computeXorFec(
    mut env: JNIEnv,
    _class: JClass,
    p1: JByteArray,
    p2: JByteArray,
    p3: JByteArray,
    p4: JByteArray,
) -> JByteArray {
    let b1 = env.convert_byte_array(&p1).unwrap_or_default();
    let b2 = env.convert_byte_array(&p2).unwrap_or_default();
    let b3 = env.convert_byte_array(&p3).unwrap_or_default();
    let b4 = env.convert_byte_array(&p4).unwrap_or_default();

    let max_len = b1.len().max(b2.len()).max(b3.len()).max(b4.len());
    let mut parity = vec![0u8; max_len];

    for (i, b) in b1.iter().enumerate() { parity[i] ^= *b; }
    for (i, b) in b2.iter().enumerate() { parity[i] ^= *b; }
    for (i, b) in b3.iter().enumerate() { parity[i] ^= *b; }
    for (i, b) in b4.iter().enumerate() { parity[i] ^= *b; }

    match env.byte_array_from_slice(&parity) {
        Ok(arr) => arr,
        Err(_) => env.new_byte_array(0).unwrap(),
    }
}
