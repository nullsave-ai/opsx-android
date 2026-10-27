use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jint, jlong};
use crc32fast::Hasher;
use chacha20::ChaCha20;
use chacha20::cipher::{KeyIvInit, StreamCipher};

// ==============================================================================
// 1. عمليات سلامة البيانات والتشفير
// ==============================================================================

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_calculateCrc32(
    env: JNIEnv,
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
    env: JNIEnv,
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
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_cryptChaCha20<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    key: JByteArray<'local>,
    nonce: JByteArray<'local>,
    data: JByteArray<'local>,
) -> JByteArray<'local> {
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

fn parse_pes_pts_ms(raw: &[u8]) -> Option<u64> {
    if raw.len() >= 14 && raw[0] == 0 && raw[1] == 0 && raw[2] == 1 {
        let flags = raw[7];
        if (flags & 0x80) != 0 {
            let b9 = raw[9] as u64;
            let b10 = raw[10] as u64;
            let b11 = raw[11] as u64;
            let b12 = raw[12] as u64;
            let b13 = raw[13] as u64;
            let pts_ticks = (((b9 >> 1) & 0x07) << 30)
                | (b10 << 22)
                | (((b11 >> 1) & 0x7F) << 15)
                | (b12 << 7)
                | ((b13 >> 1) & 0x7F);
            return Some(pts_ticks / 90); // تحويل مباشر إلى مللي ثانية دقيقة
        }
    }
    None
}

fn strip_audio_pes(raw: &[u8]) -> &[u8] {
    if raw.len() >= 9 && raw[0] == 0 && raw[1] == 0 && raw[2] == 1 && (raw[3] >= 0xC0 && raw[3] <= 0xDF) {
        let header_data_len = raw[8] as usize;
        let start = 9 + header_data_len;
        if start < raw.len() {
            return &raw[start..];
        }
    }
    // مسح استباقي للبحث عن رمز تزامن ADTS (0xFF 0xFx)
    for i in 0..raw.len().saturating_sub(2) {
        if raw[i] == 0xFF && (raw[i + 1] & 0xF0) == 0xF0 {
            return &raw[i..];
        }
    }
    raw
}

fn strip_video_pes(raw: &[u8]) -> &[u8] {
    if raw.len() >= 9 && raw[0] == 0 && raw[1] == 0 && raw[2] == 1 && (raw[3] >= 0xE0 && raw[3] <= 0xEF) {
        let header_data_len = raw[8] as usize;
        let start = 9 + header_data_len;
        if start < raw.len() {
            return &raw[start..];
        }
    }
    for i in 0..raw.len().saturating_sub(4) {
        if raw[i] == 0 && raw[i + 1] == 0 && ((raw[i + 2] == 1) || (raw[i + 2] == 0 && raw[i + 3] == 1)) {
            return &raw[i..];
        }
    }
    raw
}

#[no_mangle]
pub extern "system" fn Java_com_oneplus_opsx_OpsxNativeBridge_nativeDemuxTsToOpsx<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ts_data: JByteArray<'local>,
    base_pts: jlong,
) -> JByteArray<'local> {
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
                    let pts_ms = parse_pes_pts_ms(&video_buffer).unwrap_or(current_video_pts);
                    let clean_video = strip_video_pes(&video_buffer);
                    let is_key = clean_video.windows(5).any(|w| w == [0, 0, 0, 1, 0x65] || w == [0, 0, 0, 1, 0x67]);
                    pack_opsx_chunk_native(&mut opsx_output, 1, is_key, pts_ms, clean_video);
                    video_buffer.clear();
                    current_video_pts = pts_ms + 20; // 50fps = 20ms per frame
                }
            }
            video_buffer.extend_from_slice(payload_data);
        } else if pid == audio_pid {
            if pusi {
                if !audio_buffer.is_empty() {
                    let pts_ms = parse_pes_pts_ms(&audio_buffer).unwrap_or(current_audio_pts);
                    let clean_audio = strip_audio_pes(&audio_buffer);
                    if clean_audio.len() >= 7 {
                        pack_opsx_chunk_native(&mut opsx_output, 2, true, pts_ms, clean_audio);
                    }
                    audio_buffer.clear();
                    current_audio_pts = pts_ms + 21; // 1024 samples @ 48kHz = ~21.3ms
                }
            }
            audio_buffer.extend_from_slice(payload_data);
        }
    }

    if !video_buffer.is_empty() {
        let pts_ms = parse_pes_pts_ms(&video_buffer).unwrap_or(current_video_pts);
        let clean_video = strip_video_pes(&video_buffer);
        pack_opsx_chunk_native(&mut opsx_output, 1, false, pts_ms, clean_video);
    }
    if !audio_buffer.is_empty() {
        let pts_ms = parse_pes_pts_ms(&audio_buffer).unwrap_or(current_audio_pts);
        let clean_audio = strip_audio_pes(&audio_buffer);
        if clean_audio.len() >= 7 {
            pack_opsx_chunk_native(&mut opsx_output, 2, true, pts_ms, clean_audio);
        }
    }

    match env.byte_array_from_slice(&opsx_output) {
        Ok(arr) => arr,
        Err(_) => env.new_byte_array(0).unwrap(),
    }
}

fn pack_opsx_chunk_native(out: &mut Vec<u8>, track_id: u8, is_key: bool, pts_ms: u64, payload: &[u8]) {
    let mut header_24 = [0u8; 24];
    header_24[0] = track_id;
    header_24[1] = track_id;
    header_24[2] = if is_key { 1 } else { 0 };
    header_24[3] = 0;
    header_24[4..12].copy_from_slice(&pts_ms.to_be_bytes());
    header_24[12..20].copy_from_slice(&pts_ms.to_be_bytes());
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
