use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jint, jlong};
use crc32fast::Hasher;
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};

// ==============================================================================
// 1. عمليات سلامة البيانات والتشفير و FEC
// ==============================================================================

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

// ==============================================================================
// 2. محرك رست عالي الأداء: فك حزم MPEG-TS وتحويلها فورياً إلى OPSX (Zero-GC Demuxer)
// ==============================================================================

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_nativeDemuxTsToOpsx(
    mut env: JNIEnv,
    _class: JClass,
    ts_data: JByteArray,
    base_pts: jlong,
) -> JByteArray {
    let ts_bytes = match env.convert_byte_array(&ts_data) {
        Ok(b) => b,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };

    let mut opsx_output = Vec::with_capacity(ts_bytes.len());
    let mut pos = 0;
    let total_len = ts_bytes.len();

    let video_pid: u16 = 256;
    let audio_pid: u16 = 257;

    let mut video_buffer = Vec::new();
    let mut audio_buffer = Vec::new();
    let mut current_video_pts: u64 = base_pts as u64;
    let mut current_audio_pts: u64 = base_pts as u64;

    while pos + 188 <= total_len {
        if ts_bytes[pos] != 0x47 {
            pos += 1;
            continue;
        }

        let packet = &ts_bytes[pos..pos + 188];
        pos += 188;

        let pusi = (packet[1] & 0x40) != 0;
        let pid = (((packet[1] & 0x1F) as u16) << 8) | (packet[2] as u16);
        let af_ctrl = (packet[3] >> 4) & 0x03;

        let mut payload_offset = 4;
        if af_ctrl == 2 || af_ctrl == 3 {
            let af_len = packet[4] as usize;
            payload_offset += 1 + af_len;
        }

        if payload_offset >= 188 {
            continue;
        }

        let payload_data = &packet[payload_offset..188];

        if pid == video_pid {
            if pusi {
                if !video_buffer.is_empty() {
                    // فحص الإطار المفتاحي (Keyframe: NAL type 5 / 7)
                    let is_key = video_buffer.windows(5).any(|w| w == [0, 0, 0, 1, 0x65] || w == [0, 0, 0, 1, 0x67]);
                    pack_opsx_chunk_native(&mut opsx_output, 1, is_key, current_video_pts, &video_buffer);
                    video_buffer.clear();
                    current_video_pts += 3600; // 90kHz / 25fps = 3600 ticks
                }
            }
            video_buffer.extend_from_slice(payload_data);
        } else if pid == audio_pid {
            if pusi {
                if !audio_buffer.is_empty() {
                    pack_opsx_chunk_native(&mut opsx_output, 2, true, current_audio_pts, &audio_buffer);
                    audio_buffer.clear();
                    current_audio_pts += 1920; // 48kHz audio ticks
                }
            }
            audio_buffer.extend_from_slice(payload_data);
        }
    }

    // تفريغ الإطارات الأخيرة
    if !video_buffer.is_empty() {
        pack_opsx_chunk_native(&mut opsx_output, 1, false, current_video_pts, &video_buffer);
    }
    if !audio_buffer.is_empty() {
        pack_opsx_chunk_native(&mut opsx_output, 2, true, current_audio_pts, &audio_buffer);
    }

    match env.byte_array_from_slice(&opsx_output) {
        Ok(arr) => arr,
        Err(_) => env.new_byte_array(0).unwrap(),
    }
}

// دالة رست سريعة لتغليف مقطع OPSX (28 بايت رأس + حمولة + CRC32)
fn pack_opsx_chunk_native(out: &mut Vec<u8>, track_id: u8, is_key: bool, pts: u64, payload: &[u8]) {
    let mut header_24 = [0u8; 24];
    header_24[0] = track_id; // Chunk Type
    header_24[1] = track_id; // Track ID
    header_24[2] = if is_key { 1 } else { 0 }; // Flags
    header_24[3] = 0; // Reserved
    header_24[4..12].copy_from_slice(&pts.to_be_bytes());
    header_24[12..20].copy_from_slice(&pts.to_be_bytes()); // DTS
    header_24[20..24].copy_from_slice(&(payload.len() as u32).to_be_bytes());

    let mut h_hasher = Hasher::new();
    h_hasher.update(&header_24);
    let h_crc = h_hasher.finalize();

    let mut d_hasher = Hasher::new();
    d_hasher.update(payload);
    let d_crc = d_hasher.finalize();

    out.extend_from_slice(&header_24);
    out.extend_from_slice(&h_crc.to_be_bytes());
    out.extend_from_slice(payload);
    out.extend_from_slice(&d_crc.to_be_bytes());
}
